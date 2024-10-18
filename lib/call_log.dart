import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:intl/intl.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// main call_log plugin class
class CallLog {
  static const Iterable<CallLogEntry> _EMPTY_RESULT =
      Iterable<CallLogEntry>.empty();
  static const MethodChannel _channel = MethodChannel('com.example.calllog');

  /// Get all call history log entries. Permissions are handled automatically
  static Future<Iterable<CallLogEntry>> get() async {
    final Iterable<dynamic>? result = await _channel.invokeMethod('get', null);
    return result?.map((dynamic m) => CallLogEntry.fromMap(m)) ?? _EMPTY_RESULT;
  }

  static Future<Iterable<CallLogEntry>> query({
    int? dateFrom,
    int? dateTo,
    int? durationFrom,
    int? durationTo,
    DateTime? dateTimeFrom,
    DateTime? dateTimeTo,
    String? name,
    String? number,
    CallType? type,
    String? numbertype,
    String? numberlabel,
    String? cachedNumberType,
    String? cachedNumberLabel,
    String? cachedMatchedNumber,
    String? phoneAccountId,
  }) async {
    assert(!(dateFrom != null && dateTimeFrom != null),
        'use only one of dateTimeFrom/dateFrom');
    assert(!(dateTo != null && dateTimeTo != null),
        'use only one of dateTimeTo/dateTo');

    //NOTE: Since we are accepting date params both as timestamps and DateTime objects
    // we need to determine which one to use
    int? _dateFrom = dateFrom;
    _dateFrom ??= dateTimeFrom?.millisecondsSinceEpoch;

    int? _dateTo = dateTo;
    _dateTo ??= dateTimeTo?.millisecondsSinceEpoch;

    final Map<String, String?> params = <String, String?>{
      'dateFrom': _dateFrom?.toString(),
      'dateTo': _dateTo?.toString(),
      'durationFrom': durationFrom?.toString(),
      'durationTo': durationTo?.toString(),
      'name': name,
      'number': number,
      'type': type?.index == null ? null : (type!.index + 1).toString(),
      'cachedNumberType': cachedNumberType,
      'cachedNumberLabel': cachedNumberLabel,
      'cachedMatchedNumber': cachedMatchedNumber,
      'phoneAccountId': phoneAccountId,
    };
    final Iterable<dynamic>? records =
        await _channel.invokeMethod('query', params);
    return records?.map((dynamic m) => CallLogEntry.fromMap(m)) ??
        _EMPTY_RESULT;
  }
  // Utility function to remove special symbols from phone numbers

  // Start a timer to check the call status every 5 seconds
  void _startCallCheckTimer() {
    _callCheckTimer?.cancel(); // Cancel any existing timer
    _callCheckTimer = Timer.periodic(const Duration(seconds: 5), (timer) {
      getCallLogWidthRecording();
    });
  }

  // Stop the call check timer
  void _stopCallCheckTimer() {
    _callCheckTimer?.cancel();
  }

  Timer? _callCheckTimer;
  RxString recordingPath = ''.obs;

  Future<void> callStart(String phoneNumber) async {
    await _makePhoneCallData(phoneNumber);
  }

  Future<void> _makePhoneCallData(String phoneNumber) async {
    try {
      await _channel.invokeMethod(
          'makeCall', {'number': removeSpecialSymbols(phoneNumber)}).then((e) {
        _startCallCheckTimer(); // Start checking call status every 5 seconds
      });
    } on PlatformException catch (e) {
      throw 'Failed to make call: ${e.message}';
    }
  }

  // Utility function to remove special symbols from phone numbers
  String removeSpecialSymbols(String input) {
    final RegExp regExp = RegExp(r'[^a-zA-Z0-9]');
    return input.replaceAll(regExp, '');
  }

  Future<void> getCallLogWidthRecording() async {
    try {
      var storagestatus = await Permission.manageExternalStorage.request();
      var phonestatus = await Permission.phone.request();

      if (storagestatus.isGranted && phonestatus.isGranted) {
        // Check if the call is still active
        final bool callActive =
            await _channel.invokeMethod('checkForActiveCall');

        if (callActive) {
          if (kDebugMode) {
            print('Call is active. Waiting for disconnection...');
          }
        } else {
          if (kDebugMode) {
            print('Call is no longer active.');
          }
          await callDisconnected();
        }
      } else {
        if (kDebugMode) {
          print('Permissions denied. Please grant the necessary permissions.');
        }
      }
    } on PlatformException catch (e) {
      if (kDebugMode) {
        print("Failed to check call state: '${e.message}'.");
      }
    }
  }

  Future<void> callDisconnected() async {
    try {
      bool isCallDisconnected =
          await _channel.invokeMethod('endUserDisconected');

      if (isCallDisconnected) {
        _stopCallCheckTimer(); // Stop the timer after disconnection
        await uploadCallRecoding();
        if (kDebugMode) {
          print("Call was disconnected by the user.");
        }
      } else {
        if (kDebugMode) {
          print("No active call or the call was not disconnected by the user.");
        }
      }
    } catch (e) {
      if (kDebugMode) {
        print("Error in call disconnection logic: $e");
      }
    }
  }

  Future<String> afterCallEnd() async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    if (Platform.isAndroid) {
      try {
        String fileTime1 =
            DateFormat('yyyy-MM-dd HH:mm').format(DateTime.now());
        String recPath = '';
        recPath =
            List<String>.from(await _channel.invokeMethod('getCallRecordings', {
          'filterRecording': fileTime1,
          'selectedPath': '${prefs.getString('recordingPath')}'
        }))
                .first;

        if (recPath.contains('.')) {
          recordingPath.value = recPath;
          return recPath;
        } else {
          String fileTime = DateFormat('yyyy-MM-dd HH:mm')
              .format(DateTime.now().add(const Duration(seconds: 30)));
          recPath = List<String>.from(await _channel.invokeMethod(
                  'getCallRecordings', {'filterRecording': fileTime}))
              .first;
          recordingPath.value = recPath;
          return recPath;
        }
      } catch (e) {
        return '';
      }
    } else {
      return '';
    }
  }

  Future<Map<String, dynamic>> uploadCallRecoding() async {
    return await afterCallEnd().then((path) async {
      Future<Map<String, dynamic>> processCallLog() async {
        Iterable<CallLogEntry> _callLogs = [];
        Iterable<CallLogEntry> entries = await CallLog.get();
        _callLogs = entries;
        var lastEntry = _callLogs.first;
        String callLogTime = DateFormat('yyyy-MM-dd HH:mm:ss')
            .format(DateTime.fromMillisecondsSinceEpoch(lastEntry.timestamp!));

        String callEndTime = DateFormat('yyyy-MM-dd HH:mm:ss').format(
            DateTime.fromMillisecondsSinceEpoch(lastEntry.timestamp!)
                .add(Duration(seconds: lastEntry.duration ?? 0)));

        var callData = {
          "mobile": lastEntry.number,
          "start": callLogTime,
          "end": callEndTime,
          "duration": lastEntry.duration?.toString(),
          "recording": path
        };

        return callData;
      }

      if (path.isNotEmpty) {
        return await processCallLog();
      } else {
        await Future.delayed(const Duration(seconds: 10));
        return await processCallLog();
      }
    });
  }
}

///method for returning the callType
CallType getCallType(int n) {
  if (n == 100) {
    //return the wifi outgoing call
    return CallType.wifiOutgoing;
  } else if (n == 101) {
    //return wifiIncoming call
    return CallType.wifiIncoming;
  } else if (n >= 1 && n <= 8) {
    return CallType.values[n - 1];
  } else {
    return CallType.unknown;
  }
}

/// PODO for one call log entry
class CallLogEntry {
  /// constructor
  CallLogEntry({
    this.name,
    this.number,
    this.formattedNumber,
    this.callType,
    this.duration,
    this.timestamp,
    this.cachedNumberType,
    this.cachedNumberLabel,
    this.simDisplayName,
    this.phoneAccountId,
  });

  /// constructor creating object from provided map
  CallLogEntry.fromMap(Map<dynamic, dynamic> m) {
    name = m['name'];
    number = m['number'];
    formattedNumber = m['formattedNumber'];
    callType = getCallType(m['callType']);
    duration = m['duration'];
    timestamp = m['timestamp'];
    cachedNumberType = m['cachedNumberType'];
    cachedNumberLabel = m['cachedNumberLabel'];
    cachedMatchedNumber = m['cachedMatchedNumber'];
    simDisplayName = m['simDisplayName'];
    phoneAccountId = m['phoneAccountId'];
  }

  /// contact name
  String? name;

  /// contact number
  String? number;

  /// formatted number based on phone locales
  String? formattedNumber;

  /// type of call entry. see CallType
  CallType? callType;

  /// duration in seconds
  int? duration;

  /// unix timestamp of call start
  int? timestamp;

  /// todo comment
  int? cachedNumberType;

  /// todo comment
  String? cachedNumberLabel;

  /// todo comment
  String? cachedMatchedNumber;

  /// SIM display name
  String? simDisplayName;

  /// PHONE account id
  String? phoneAccountId;
}

/// All possible call types
enum CallType {
  /// incoming call
  incoming,

  /// outgoing call
  outgoing,

  /// missed incoming call
  missed,

  /// voicemail call
  voiceMail,

  /// rejected incoming call
  rejected,

  /// blocked incoming call
  blocked,

  /// todo comment
  answeredExternally,

  /// unknown type of call
  unknown,

  /// wifi incoming
  wifiIncoming,

  ///wifi outgoing
  wifiOutgoing,
}


// mainProgram() async {
//   Map<String, dynamic> callData = await CallLog().uploadCallRecoding();

//   print("Mobile: ${callData['mobile']}");
//   print("Start Time: ${callData['start']}");
//   print("End Time: ${callData['end']}");
//   print("Duration: ${callData['duration']}");
//   print("Recording Path: ${callData['recording']}");
// }

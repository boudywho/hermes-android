import 'package:flutter_test/flutter_test.dart';
import 'package:hermes_android/core/services/ws_client.dart';

void main() {
  test('reasoning selector includes Max as a selectable effort', () {
    expect(WsClient.validReasoningEfforts, contains('max'));
    expect(WsClient.normalizeReasoningEffort('max'), 'max');
  });
}

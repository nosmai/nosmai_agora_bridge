import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nosmai_agora_bridge_example/main.dart';

void main() {
  testWidgets('shows the beta example shell', (tester) async {
    await tester.pumpWidget(const MyApp());
    expect(find.text('Nosmai + Agora Beta'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });
}

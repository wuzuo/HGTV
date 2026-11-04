// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/foundation.dart';

import 'api.dart';
import 'models.dart';
import 'store.dart';

/// Shared dependencies for every screen: the bridge API, local preferences/library and the
/// currently restored account. Mirrors the two-object injection used by the other clients.
class AppSession extends ChangeNotifier {
  AppSession({required this.api, required this.store});

  final Api api;
  final LocalStore store;

  AccountInfo? account;
  String bridgeStatus = '';

  /// 本地设备身份配置的一句话状态（空串表示没有配置，走自动注册）。
  String identityStatus = '';

  bool get loggedIn => account?.loggedIn == true && (account?.id?.isNotEmpty ?? false);

  void setAccount(AccountInfo? value) {
    account = value;
    notifyListeners();
  }

  void setBridgeStatus(String value) {
    bridgeStatus = value;
    notifyListeners();
  }

  void setIdentityStatus(String value) {
    if (identityStatus == value) return;
    identityStatus = value;
    notifyListeners();
  }

  /// Uploads watch progress to the red-fruit account when the user is signed in.
  Future<void> upload(WatchRecord record, int episodeCount) async {
    final accountId = account?.id;
    if (!loggedIn || accountId == null) return;
    await api.upload(
      series: record.series,
      episodeId: record.episodeId,
      episodeIndex: record.episodeIndex,
      position: record.position,
      duration: record.duration,
      episodeCount: episodeCount,
      accountId: accountId,
    );
  }
}

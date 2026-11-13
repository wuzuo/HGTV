// SPDX-License-Identifier: GPL-3.0-only
import 'dart:io';

import 'package:flutter/material.dart';

import '../app_info.dart';
import '../app_session.dart';
import '../store.dart';
import '../widgets.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.session});

  final AppSession session;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      // 设备身份状态由 5 秒轮询更新，设置页要跟着刷新，不能只听本地偏好。
      animation: Listenable.merge([session.store, session]),
      builder: (context, _) {
        final store = session.store;
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            Text(
              '设置',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 20),
            _section(
              context,
              '外观',
              [
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'system', label: Text('跟随系统'), icon: Icon(Icons.brightness_auto)),
                    ButtonSegment(value: 'light', label: Text('浅色'), icon: Icon(Icons.light_mode)),
                    ButtonSegment(value: 'dark', label: Text('深色'), icon: Icon(Icons.dark_mode)),
                  ],
                  selected: {store.themeMode},
                  onSelectionChanged: (value) => store.updateThemeMode(value.first),
                ),
              ],
            ),
            const SizedBox(height: 20),
            _section(context, '播放设置', [
              const _SettingLabel('默认清晰度'),
              ChipRail(
                options: const [('480', '480P'), ('720', '720P'), ('1080', '1080P')],
                selected: '${store.quality}',
                onSelect: (value) => store.updateQuality(int.parse(value)),
              ),
              const SizedBox(height: 12),
              const _SettingLabel('默认倍速'),
              ChipRail(
                options: [
                  for (final speed in speeds)
                    (
                      '$speed',
                      speed == speed.roundToDouble() ? '${speed.toInt()}×' : '$speed×',
                    ),
                ],
                selected: '${store.speed}',
                onSelect: (value) => store.updateSpeed(double.parse(value)),
              ),
              const SizedBox(height: 4),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('自动下一集'),
                subtitle: const Text('本集播放结束后直接续播下一集'),
                value: store.autoNext,
                onChanged: store.updateAutoNext,
              ),
              Text(
                '清晰度与倍速会同步到播放器；播放中也可以随时切换。',
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ]),
            const SizedBox(height: 20),
            _section(context, '播放器', [
              Text(
                '内置 libmpv（media_kit）解码，支持清晰度切换、倍速、断点续播与弹幕叠加，'
                '无需单独安装 VLC。',
                style: TextStyle(
                  fontSize: 13,
                  height: 1.6,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ]),
            const SizedBox(height: 20),
            _section(context, '设备身份', [
              Text(
                _identityHeadline,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 6),
              SelectableText(
                _identityDetail,
                style: TextStyle(
                  fontSize: 11,
                  height: 1.7,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  OutlinedButton.icon(
                    onPressed: () => _openFolder(
                      context,
                      session.identityConfigured && session.identityFile.isNotEmpty
                          ? File(session.identityFile).parent.path
                          : _programDir,
                    ),
                    icon: const Icon(Icons.folder_open, size: 18),
                    label: Text(session.identityConfigured ? '打开所在文件夹' : '打开程序目录'),
                  ),
                  const SizedBox(width: 10),
                  OutlinedButton(
                    onPressed: () => _showIdentityHelp(context),
                    child: const Text('如何配置'),
                  ),
                ],
              ),
            ]),
            const SizedBox(height: 20),
            _SigningSection(session: session),
            const SizedBox(height: 20),
            _section(context, '关于', [
              Text('${AppInfo.product} ${AppInfo.version}'),
              const SizedBox(height: 4),
              Text(
                '内容解析、登录与解密由本机 Java 子进程完成，界面只通过随机令牌的 127.0.0.1 回环接口访问。',
                style: TextStyle(
                  fontSize: 12,
                  height: 1.6,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  OutlinedButton(
                    onPressed: () => _showLicense(context),
                    child: const Text('许可与说明'),
                  ),
                  const SizedBox(width: 10),
                  OutlinedButton.icon(
                    onPressed: () => _openFolder(context, AppDirs.data.path),
                    icon: const Icon(Icons.folder_open, size: 18),
                    label: const Text('打开数据目录'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              SelectableText(
                '本机数据目录：${AppDirs.data.path}\n本机服务日志：${AppDirs.data.path}\\bridge.log',
                style: TextStyle(
                  fontSize: 11,
                  height: 1.6,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              if (session.bridgeStatus.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  session.bridgeStatus,
                  style: TextStyle(
                    fontSize: 11,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ]),
          ],
        );
      },
    );
  }

  /// 打包布局下 exe 所在目录就是程序目录；`flutter run` 时是 Debug 输出目录。
  String get _programDir => File(Platform.resolvedExecutable).parent.path;

  String get _userHome =>
      Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'] ?? '.';

  String get _identityHeadline {
    if (session.identityRejected) return '配置已失效，当前按自动注册播放';
    if (session.identityConfigured) {
      return session.identityStatus.isEmpty ? '已读取设备身份配置' : session.identityStatus;
    }
    return '未放置配置，当前每次播放都会注册一台新设备';
  }

  String get _identityDetail {
    if (session.identityConfigured && session.identityFile.isNotEmpty) {
      return '读取位置：${session.identityFile}';
    }
    return '把 device-identity.txt 放到下面任一位置，重启客户端即生效：\n'
        '· $_programDir${Platform.pathSeparator}device-identity.txt\n'
        '· $_userHome${Platform.pathSeparator}.hgtv${Platform.pathSeparator}device-identity.txt';
  }

  void _showIdentityHelp(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('设备身份配置'),
        content: const SizedBox(
          width: 480,
          child: SingleChildScrollView(
            child: Text(
              'HGTV 可以复用一台真实设备的播放身份，省掉每次播放前的设备注册。'
              '身份来自官方「红果免费短剧」客户端，在已 root 的手机上用配套小工具读出后，'
              '存成纯文本即可。\n\n'
              '文件名固定为 device-identity.txt，内容三行：\n'
              'device_id=...\n'
              'install_id=...\n'
              'cdid=...\n\n'
              '放置位置（任选其一）：\n'
              '1. 程序目录，即 hgtv_windows.exe 同级；\n'
              '2. 用户目录下的 .hgtv 文件夹，即 %USERPROFILE%\\.hgtv\\\n\n'
              '放好后重启客户端，底部状态栏与这一页都会显示「已读取」。'
              '不放也能正常播放，只是每集多注册一次设备。\n\n'
              '注意：同一份身份不要和手机上的官方客户端同时播放，否则会被判成设备异常。',
              style: TextStyle(fontSize: 13, height: 1.7),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('关闭')),
        ],
      ),
    );
  }

  void _showLicense(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('许可与说明'),
        content: const SizedBox(
          width: 460,
          child: SingleChildScrollView(
            child: Text(
              'HGTV 桌面客户端以 GPL-3.0-only 发布，源码地址：\n'
              'https://github.com/wuzuo/HGTV\n\n'
              '界面使用 Flutter 与 Material 3；播放使用 media_kit / libmpv（LGPL-2.1+）；'
              '视频解析与账号逻辑复用 kotlin-tv 内核与本机 Java 回环服务。\n\n'
              '本客户端仅用于个人学习与已授权内容的播放，所有剧集版权归原权利人所有。'
              '应用不内置任何账号密码，也不会把登录态上传到第三方服务器。',
              style: TextStyle(fontSize: 13, height: 1.7),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('关闭')),
        ],
      ),
    );
  }

  Future<void> _openFolder(BuildContext context, String path) async {
    try {
      await Process.start('explorer.exe', [path], runInShell: false);
    } catch (problem) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('无法打开目录：$problem')));
    }
  }
}

Widget _section(BuildContext context, String title, List<Widget> children) {
  final scheme = Theme.of(context).colorScheme;
  return Container(
    width: double.infinity,
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: scheme.surfaceContainerLow,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionTitle(title),
        ...children,
      ],
    ),
  );
}

/// 真机签名服务：地址既写进 settings.json，也通过 /signing 端点下发给本机服务，
/// 因此改完当场生效，不必重启客户端。
class _SigningSection extends StatefulWidget {
  const _SigningSection({required this.session});

  final AppSession session;

  @override
  State<_SigningSection> createState() => _SigningSectionState();
}

class _SigningSectionState extends State<_SigningSection> {
  late final TextEditingController _controller = TextEditingController(
    text: widget.session.store.signingService,
  );
  String _status = '';
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _apply(String value) async {
    final endpoint = value.trim().replaceAll(RegExp(r'/+$'), '');
    if (endpoint.isNotEmpty &&
        !(endpoint.startsWith('http://') || endpoint.startsWith('https://'))) {
      setState(() => _status = '地址要以 http:// 或 https:// 开头');
      return;
    }
    setState(() {
      _busy = true;
      _status = '';
    });
    try {
      final state = await widget.session.api.setSigningService(endpoint);
      if (!mounted) return;
      widget.session.store.updateSigningService(endpoint);
      setState(() {
        _status = endpoint.isEmpty
            ? '已关闭远程签名，播放改回本机签名'
            : '已启用并立即生效 · 成功 ${state['successes'] ?? 0} 次 / '
                '失败 ${state['failures'] ?? 0} 次';
      });
    } catch (problem) {
      if (mounted) setState(() => _status = '保存失败：$problem');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return _section(context, '签名服务', [
      Text(
        '播放接口的 X-Medusa 由真机上的红果签名服务计算。手机装好独立签名版、'
        '与电脑在同一局域网后，把它的地址填在这里；服务会顺带把设备身份对齐。',
        style: TextStyle(fontSize: 12, height: 1.6, color: scheme.onSurfaceVariant),
      ),
      const SizedBox(height: 12),
      TextField(
        controller: _controller,
        enabled: !_busy,
        decoration: const InputDecoration(
          labelText: '服务地址',
          hintText: 'http://192.168.0.101:18089',
          isDense: true,
          border: OutlineInputBorder(),
        ),
        onSubmitted: _busy ? null : (value) => _apply(value),
      ),
      const SizedBox(height: 10),
      Row(
        children: [
          FilledButton(
            onPressed: _busy ? null : () => _apply(_controller.text),
            child: const Text('保存并启用'),
          ),
          const SizedBox(width: 10),
          OutlinedButton(
            onPressed: _busy
                ? null
                : () {
                    _controller.clear();
                    _apply('');
                  },
            child: const Text('关闭'),
          ),
        ],
      ),
      if (_status.isNotEmpty) ...[
        const SizedBox(height: 10),
        Text(
          _status,
          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
        ),
      ],
      const SizedBox(height: 6),
      Text(
        '留空＝只用本机签名（播放接口可能返回空包）。手机重启后打开一次红果并让它'
        '留在后台；手机 IP 变了要回来改地址。',
        style: TextStyle(fontSize: 11, height: 1.6, color: scheme.onSurfaceVariant),
      ),
    ]);
  }
}

class _SettingLabel extends StatelessWidget {
  const _SettingLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Text(text, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
  );
}

// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter_test/flutter_test.dart';
import 'package:hgtv_windows/models.dart';

/// 首页「继续观看」只取本机历史里最近的一条，文案要能直接回答「哪部、第几集、看到哪」。
/// 这些断言守住四件事：按更新时间取、集号从 1 开始、已看完指向下一集、空历史不占位。
WatchRecord _record(
  int id,
  int updatedAt, {
  bool completed = false,
  double position = 0,
  double duration = 300,
}) => WatchRecord(
  series: Series(id: '$id', title: '第 $id 部'),
  episodeId: '$id-1',
  episodeIndex: id,
  position: position,
  duration: duration,
  completed: completed,
  updatedAt: updatedAt,
);

void main() {
  test('按更新时间取最近一条，不依赖写入顺序', () {
    final latest = latestHistory([
      _record(1, 1000, position: 65),
      _record(2, 9000, position: 125),
      _record(3, 5000, position: 30),
    ]);
    expect(latest, isNotNull);
    expect(latest!.series.title, '第 2 部');
    expect(latest.resumeWhere, '第 3 集 · 02:05');
  });

  test('整集看完时指向下一集，而不是停在 00:00', () {
    final latest = latestHistory([
      _record(1, 100, completed: true, position: 300, duration: 300),
    ]);
    expect(latest!.resumeWhere, '接着看下一集');
    expect(latest.label, '第 2 集 · 已看完');
  });

  test('没有记录时返回 null，首页不显示入口', () {
    expect(latestHistory(const []), isNull);
  });

  test('旧数据没有更新时间时退回第一条', () {
    final latest = latestHistory([_record(1, 0, position: 10), _record(2, 0, position: 20)]);
    expect(latest!.series.title, '第 1 部');
  });

  test('进度按总时长换算并夹在 0..1', () {
    expect(_record(1, 1, position: 150, duration: 300).progress, 0.5);
    expect(_record(1, 1, position: 400, duration: 300).progress, 1.0);
    expect(_record(1, 1, position: 10, duration: 0).progress, 0.0);
  });

  test('位置异常时不写出负时间', () {
    expect(_record(1, 1, position: -5).clock, '00:00');
  });
}

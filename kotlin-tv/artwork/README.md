# HGTV 品牌资源

图标使用统一的「渐变播放键」标识：橙红到青绿的圆角三角外轮廓、中央镂空三角，透明背景，
主体居中（四周留约 6% 安全边距）。同一份 256×256 源图导出全部尺寸，不重新绘制主体。

- `hgtv-icon-c3.png`：256×256 透明底主图，作为桌面图标、应用内标识与 Windows exe 图标的来源。
- `hgtv-tv-banner.png`：1280×720 TV 横幅预览图，图标居左、右侧「HGTV」字样。
- `../app/src/main/res/mipmap-*/ic_launcher.png`：mdpi 48、hdpi 72、xhdpi 96、xxhdpi 144、xxxhdpi 192 像素桌面图标。
- `../app/src/main/res/drawable-nodpi/brand_mark.png`：512 像素应用内标识，由 `drawable/app_icon.xml` 引用。
- `../app/src/main/res/drawable-xhdpi/tv_banner.png`：320×180 像素 TV 启动器横幅。

图标使用完整位图，不做透明拆层或自适应前景分层；桌面外形、圆角与遮罩由启动器处理。
缩放使用 Lanczos 重采样并保留透明通道。电视上的实际显示效果需安装测试包验收。

## 导出方式

```sh
ffmpeg -i hgtv-icon-c3.png -vf "scale=192:192:flags=lanczos" -pix_fmt rgba ic_launcher.png
```

横幅由图标与渐变底色合成，左图标右文字，320×180 与 1280×720 两版排版一致。

## TV 横幅预览

![HGTV 横幅](hgtv-tv-banner.png)

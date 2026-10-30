# HGTV 品牌资源

本轮采用用户选定的 C3：蓝紫柔和渐变背景、橙红银幕与叶片、浅色播放三角。图像使用内置 image_gen 生成；安装包资源仅做尺寸转换，不重新绘制已选定主体。

- `guoying-icon-c3.png`：选定的方形主图，作为所有普通桌面图标和应用内标识的来源。
- `guoying-tv-banner.png`：同风格 TV 横幅主图，文字为「HGTV」。
- `../app/src/main/res/mipmap-*/ic_launcher.png`：mdpi 48、hdpi 72、xhdpi 96、xxhdpi 144、xxxhdpi 192 像素桌面图标。
- `../app/src/main/res/drawable-nodpi/brand_mark.png`：512 像素应用内标识，由 `drawable/app_icon.xml` 引用。
- `../app/src/main/res/drawable-xhdpi/tv_banner.png`：320×180 像素 TV 启动器横幅。

本轮使用完整位图图标，保留 C3 的主体和背景，不使用透明拆层实验产生的杂点前景。桌面外形和边框由启动器处理。回归检查覆盖 API 23/26，电视上实际显示效果由用户安装测试包验收。

## C3 提示词

Use case: precise-object-edit. Refine this existing HGTV app icon into a balanced final-candidate C3. CHANGE ONLY the blue/purple background. Keep the foreground emblem EXACTLY unchanged: orange-coral gradient rounded television/fruit outline, both leaves and their cutouts, cream play triangle, all contours, geometry, proportions, positioning and scale. Do not redesign or recolor the emblem. The background should sit halfway between the original very dark navy and this reference's very bright saturated electric blue/violet: refined mid-dark blue and muted indigo-purple, visibly colorful but calm. Reduce the electric saturation considerably (roughly a third), modestly lower overall background brightness, eliminate the strong upper-left cyan highlight and any lower-right violet hotspot. Use one extremely smooth broad gradient with a gentle central luminosity lift and slightly deeper edges. Suggested background palette: rich muted blue #263E91 toward left, soft indigo #3E438D around center, subdued violet #514184 toward right; keep corners blue/indigo, NEVER black. This palette is a direction, not literal blocks. The entire background including the screen interior and leaf cutouts must be one continuous coherent gradient. The warm emblem must be the clear visual focal point with strong contrast, especially at small launcher-icon sizes. Flat clean premium finish, no texture, grain, glows, rays, stars, neon outlines, new highlights, new shadows, text or watermark. Preserve square full bleed and all safe margins. Output ONE finished square icon only, no mockup or contact sheet.

## TV 横幅提示词

Use case: logo-brand. Make a polished 16:9 Android TV launcher banner for HGTV using the supplied APPROVED C3 icon as the exact visual reference. Landscape composition, full-bleed muted royal-blue-to-indigo-purple gradient, matching C3's calm background and color palette. On the left place the exact C3 orange-coral fruit-shaped TV-screen emblem with its two leaves and cream play triangle, preserving its proportions and silhouette, no icon tile or ring behind it. On the right place large, impeccably legible warm ivory typography reading exactly: HGTV. One line, clean bold modern Chinese sans serif, generous spacing, balanced vertical center. The logo and name must both be readable when downscaled to 320x180. Keep all content comfortably inside the central 80% with generous margins. No subtitle, no marketing claim, no other text, no shadows, no texture, no new decorative objects, no white border, no rounded outer mask. A clean production launcher banner, not a scene or device mockup.

## TV 横幅预览

![HGTV 横幅](guoying-tv-banner.png)

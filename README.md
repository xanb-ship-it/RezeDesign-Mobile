# Reze Design Mobile

> MMD 设计与渲染，装进你的口袋。

[Reze Design]是一个运行在浏览器里的 WebGPU MMD 场景编辑器。本项目是它的 **Android 移动端**，基于 [AmyangXYZ/reze-design](https://github.com/AmyangXYZ/reze-design) 源码开发的原生应用，内置离线资源，支持一键在**离线 / 联网**之间切换。

---

## 核心功能

### MMD 模型与动作
直接读取 PMX 模型和 VMD 动作、表情、镜头，支持 IK 以及头发、衣服的物理演算，可多人同台。

### 视频导出
4K 60 帧，4× MSAA 逐帧离线渲染，不掉帧，音画对齐；支持绿幕和透明通道导出。

### 舞台场景
支持 PMX 舞台文件夹，也支持从 Blender 导出的 GLB 舞台，灯光、太阳和环境会一起导入。

### 渲染风格
《深空之眼》《鸣潮》《绝区零》《崩坏：星穹铁道》等风格一键切换；也可以用节点编辑器自己连材质。

### 实时特效
雨、樱花、烟花、手部光带、舞台灯、歌词字幕等 WGSL 特效，可以跟随骨骼、音乐和歌词，也可以自己编写。

### 时间轴与曲线编辑
在视图里直接拖动骨骼摆姿势，调整关键帧和贝塞尔曲线，保存为任何 MMD 工具都能读取的 VMD。

### 歌词生成口型
导入 .lrc 歌词，一键生成口型表情 VMD，支持中文、日文、韩文和英文。

### 发布分享
发布后得到一个永久链接，打开就是可以旋转视角的实时场景，别人也可以一键复制到自己的编辑器里继续创作。

---


---



### 重新构建前端资源

`assets/` 由上游 Web 工程生成，并非在本仓库编写：

```bash
# 在上游 reze-design 检出目录中执行
OFFLINE_BUILD=1 node scripts/build-offline.mjs     # -> out/
# 随后将 out/ 同步进 app/src/main/assets/，并把 _next/ 重写为 nx_assets/
```

---


---

## Reze MMD 家族

| 项目 | 职责 |
|---|---|
| [reze-engine](https://github.com/AmyangXYZ/reze-engine) | WebGPU 基础引擎，二次元角色渲染与物理，零依赖 |
| [reze-design](https://github.com/AmyangXYZ/reze-design) | **上游项目** —— MMD 设计、渲染与分享 |
| [reze-studio](https://github.com/AmyangXYZ/reze-studio) | 专业时间轴与曲线编辑器的动画制作 |
| [MiKaPo](https://github.com/AmyangXYZ/MiKaPo) | 浏览器实时动作捕捉，直接导出 VMD |
| [reze-rig](https://github.com/AmyangXYZ/reze-rig) | 将 FBX 动画重定向为 MMD VMD |

---

## 许可证

**AGPL-3.0-or-later**，与上游一致。

本项目是 [AmyangXYZ/reze-design](https://github.com/AmyangXYZ/reze-design) 的衍生作品。由于 AGPL-3.0 是强著佐权协议，本仓库以相同条款分发，任何修改版本的再分发都必须：

- 保留本许可证及上游署名；
- 声明已做出修改；
- 提供对应源代码。

完整条款见 [LICENSE](LICENSE)。

---

## 致谢

- **AmyangXYZ** —— [reze-design](https://github.com/AmyangXYZ/reze-design)、[reze-engine](https://github.com/AmyangXYZ/reze-engine) 及 Reze MMD 家族其余成员。编辑器、渲染器与物理系统全部出自他们之手。
- **Crypton Future Media** —— 初音未来，以及成就这一切的 MMD 生态。
- MMD 社区，为那些让这个圈子持续运转的模型、动作与舞台。
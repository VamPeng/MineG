# MineG App 主题色与使用色调规范

状态：正式主题基线
适用范围：Android、iOS、HarmonyOS、移动端原型及后续品牌物料
主题来源：[mineg_logo.png](../../../mineg_logo.png)

## 1. 主题结论

MineG 的主题色不是单一珊瑚红，也不是原设计中的鼠尾草绿，而是直接取自 Logo 填充的暖橙红渐变：

```text
#FD7106 → #FD5033 → #FD374B
```

- 暖橙代表家庭温度、回忆和陪伴。
- 暖红代表亲密关系、珍贵内容和品牌识别。
- 米白色承载主要界面，避免大面积橙红造成视觉疲劳。
- 鼠尾草绿保留为备份完成、同步成功等功能状态色，不再承担品牌主色或安全背书职责。

## 2. 品牌主题 Token

| Token | 色值 | 用途 |
|---|---|---|
| `brand.gradient.start` | `#FD7106` | Logo 渐变起点、品牌暖橙 |
| `brand.gradient.middle` | `#FD5033` | 渐变中点、无法使用渐变时的品牌单色 |
| `brand.gradient.end` | `#FD374B` | Logo 渐变终点、品牌暖红 |
| `brand.primary` | `#FD5033` | 图标强调、开关、进度和小面积品牌元素 |
| `brand.primary.action` | `#D63B26` | 需要白色小字号文字的实体主按钮 |
| `brand.primary.pressed` | `#B92E24` | 实体主按钮按下状态 |
| `brand.primary.container` | `#FFE3DE` | 浅品牌容器、选中标签和提示背景 |
| `brand.on-primary` | `#1F1B17` | Logo 原色或品牌渐变上的常规文字 |
| `brand.on-primary-action` | `#FFFFFF` | 深色实体主按钮上的文字 |
| `brand.on-primary-container` | `#6B1A10` | 浅品牌容器上的文字和图标 |

标准品牌渐变：

```css
linear-gradient(
  135deg,
  #FD7106 0%,
  #FD5033 52%,
  #FD374B 100%
)
```

## 3. 浅色模式基础色

| Token | 色值 | 用途 |
|---|---|---|
| `surface.background` | `#FFF8F4` | App 页面基础背景 |
| `surface.default` | `#FFFFFF` | 卡片、弹窗、底部导航 |
| `surface.low` | `#FBF2EB` | 次级区块和弱分组 |
| `surface.container` | `#F5ECE5` | 输入框、列表组、设置容器 |
| `surface.high` | `#EEE3DC` | 强分组、按压反馈 |
| `text.primary` | `#1F1B17` | 标题、正文和重要数字 |
| `text.secondary` | `#6B6260` | 说明、时间和辅助信息 |
| `text.disabled` | `rgba(31, 27, 23, 0.38)` | 禁用文字和图标 |
| `icon.inactive` | `#AAA4A3` | 未选中的底部导航 Icon |
| `outline.default` | `#B9AFAA` | 输入框和控件边界 |
| `divider.default` | `#E8DDD7` | 列表分隔线 |

## 4. 功能状态色

状态色表达业务含义，不能替代品牌主题色。

| 状态 | 主色 | 浅容器 | 使用场景 |
|---|---|---|---|
| 成功 / 完成 | `#436444` | `#DCEBD9` | 备份完成、同步完成、操作成功 |
| 等待 / 警告 | `#8A4F00` | `#FFE0B2` | 等待 Wi-Fi、容量提醒、需注意 |
| 错误 / 危险 | `#BA1A1A` | `#FFDAD6` | 上传失败、删除、不可恢复操作 |
| 信息 | `#3A5F86` | `#D8E9FF` | 普通说明、系统信息、帮助提示 |

规则：

- 绿色只表示当前操作成功、任务完成或状态正常，不用于安全、隐私或加密背书。
- 红色错误色只表示失败、危险或破坏性操作；品牌暖红不能代替错误色。
- 状态不能只靠颜色表达，必须配合 Icon 或文字。

## 5. 底部导航

底部导航只显示四个 Icon，不显示可见文案；无障碍名称仍需保留。

| 导航项 | Icon 含义 |
|---|---|
| 私人空间 | 锁 |
| 家庭相册 | 房屋 |
| 备份 | 叠放照片 |
| 我的 | 人物 |

未选中状态：

- Icon 使用 `icon.inactive`，或对现有渐变 PNG 做中性灰处理。
- 不使用浅品牌色背景。

选中状态：

- Icon 保留 Logo 同源的原始橙红渐变，不重新填色。
- 仅按钮背景使用 Logo 渐变的 18% 透明版本。
- 选中背景不能使用灰色、绿色或独立的粉色。

```css
linear-gradient(
  135deg,
  rgba(253, 113, 6, 0.18) 0%,
  rgba(253, 80, 51, 0.18) 52%,
  rgba(253, 55, 75, 0.18) 100%
)
```

建议尺寸：

- 导航按钮：`60 × 50`
- Icon 布局框：`32 × 32`
- Icon 实际视觉轮廓：约 `20–26px`
- Icon 必须完整居中，不得依靠裁切放大。

## 6. 按钮与交互控件

### 品牌主按钮

- 推荐使用完整 Logo 渐变。
- 普通字号文字使用 `#1F1B17`；它在渐变三个主要色点上均达到可读对比度。
- 不要直接在 `#FD5033` 上使用小号白字，其对比度不足。

### 白字实体主按钮

- 背景使用 `#D63B26`。
- 文字使用 `#FFFFFF`。
- 按下状态使用 `#B92E24`。

### 次按钮

- 使用白色或 `surface.low` 背景。
- 边框使用 `outline.default`。
- 文字使用 `text.primary`。

### 开关、进度与焦点

- 开启状态和品牌进度使用 `brand.primary`。
- 备份完成状态改用成功绿 `#436444`。
- 输入框焦点边框使用 `brand.primary.action`，保证在浅色背景上足够清晰。

## 7. 页面色调比例

推荐使用比例：

- 70%：米白和白色表面。
- 20%：照片、视频和内容本身。
- 10%：品牌渐变、状态色和强调元素。

大面积页面背景、长文本背景和密集列表不能使用品牌渐变。MineG 的内容主体是家庭媒体，主题色应负责识别和引导，不能与照片争夺注意力。

## 8. 深色模式

| Token | 色值 |
|---|---|
| `dark.background` | `#1B1513` |
| `dark.surface` | `#241D1A` |
| `dark.surface.container` | `#2F2622` |
| `dark.text.primary` | `#FFF5F0` |
| `dark.text.secondary` | `#D8C3B9` |
| `dark.outline` | `#8E7D75` |
| `dark.brand.gradient.start` | `#FF8A3D` |
| `dark.brand.gradient.middle` | `#FF6E58` |
| `dark.brand.gradient.end` | `#FF5B72` |

深色模式仍保持同一渐变方向，但提高亮度，避免暖红在深色背景上显得浑浊。选中导航背景透明度建议为 22%。

## 9. 可读性要求

- `#FD5033` 与白色对比度约为 `3.30:1`，不能用于普通字号白色正文。
- `#FD5033` 与 `#1F1B17` 对比度约为 `5.19:1`，可用于普通字号文字。
- `#D63B26` 与白色对比度约为 `4.65:1`，可用于白字实体主按钮。
- 正文和重要操作以 `4.5:1` 为最低目标。
- 大号粗体文字以 `3:1` 为最低目标。

## 10. 禁止用法

- 不得继续把鼠尾草绿定义为 App 主主题色。
- 不得使用与 Logo 无关的独立珊瑚红或粉色作为主题替代色。
- 不得给选中 Icon 添加半透明灰色、绿色或品牌色蒙层。
- 不得通过放大并裁切 PNG 的方式展示导航 Icon。
- 不得将品牌暖红当作错误提示色。
- 不得在大面积页面背景或长文本区域使用品牌渐变。

## 11. 平台映射

三端必须使用相同 Token 名称和色值：

- Android：Compose `Color` 与 `Brush.linearGradient`。
- iOS：SwiftUI `Color` 与 `LinearGradient`。
- HarmonyOS：ArkUI `Color` 与 `LinearGradient`。

生产代码中不得从 PNG 截图取色。颜色与渐变应由 Token 明确定义，Logo 和导航 Icon 只作为视觉资产使用。

---
version: alpha
name: StudyPilot
description: "面向计算机课程备考的学习工作台，以课程目录、重点批注与连续学习笔记组织信息。"
colors:
  primary: "#4267cf"
  primary-hover: "#3454ae"
  background: "#f7f8fa"
  surface: "#ffffff"
  text: "#20242b"
  muted: "#6a707b"
  border: "#e3e6eb"
  focus: "#2369a5"
  emphasis: "#936016"
  emphasis-surface: "#fff0cd"
  danger: "#a33240"
  danger-surface: "#fff0f2"
  success: "#256950"
typography:
  display:
    fontFamily: "Bahnschrift, Microsoft YaHei, sans-serif"
  body:
    fontFamily: "Microsoft YaHei, PingFang SC, system-ui, sans-serif"
  mono:
    fontFamily: "Cascadia Code, Consolas, monospace"
rounded:
  DEFAULT: "0.625rem"
  sm: "0.375rem"
  lg: "0.875rem"
spacing:
  control-gap: "0.5rem"
  content-gap: "1.5rem"
  page-max: "76rem"
  sidebar-width: "17rem"
omitted:
  - section: components
    reason: "组件状态通过下文语义规则和同一份运行时 CSS 实现，不另建独立组件 token 副本。"
---

# StudyPilot Design System

## Overview

视觉参考为用户提供的 ChatGPT、DeepSeek、Gemini 对话页面及已确认的四屏稿。浅灰侧栏、白色正文、少量蓝色动作强调；左上角飞行员与书本标志。知识库如项目分组，最近聊天缩进列于其下。新对话以居中输入框开始，不设置宣传式 hero 或虚构学习统计。

面向使用中文界面的大学计算机课程学习者，课程正文可为中英混合；这是产品工具界面，非营销页面。只面向桌面长时间阅读与演示，不再验收窄屏。当前只提供浅色主题，无日本市场专属业务假设。

唯一醒目的表达是知识点路线旁的琥珀重点批注：优先级必须来自已保存计划，完成标记必须来自后端状态；不能把“已完成”美化成已掌握。其余位置保持安静，留出正文阅读宽度。

用户已授权实施对话式重构。主导航为“新对话 / 资料库 / 测试工具”；大纲从知识库或聊天顶部进入。资料库管理文件，测试工具为独立页面，内部左选业务、右侧操作，不与聊天并排。前端显示名采用 StudyPilot，仓库与后端包名保留 StudyAgent。

Token 所有权使用 Model B：`frontend/src/styles.css` 的 `:root` 变量是运行时唯一来源，本文镜像已选定值与解释意图。组件只引用语义变量；同一次改动同步文档与 CSS。M6 实施中的此文件描述获授权的目标设计，运行时 token 与界面验证完成后才算迁移完成。

## Colors

primary 用于安全主要动作、当前导航和链接；text 用于正文、标题；muted 用于次要解释；border 分隔信息组。emphasis/emphasis-surface 只表达有依据的学习重点。danger 用于失败与校验错误，success 用于已保存/已完成。不能只靠颜色区分状态。focus 为所有可交互元素提供清楚的外轮廓。

全局滚动条用 muted/border；hover 用 primary。强制颜色模式交给系统恢复可读性。错误、进度和来源需有文本，不能依赖色块或动画。

## Typography

display 仅用于 StudyPilot 字标与少量标题；中文标题优先黑体，不使用旧页面的宋体大标题。正文 body 为 14–15px、约 1.75 行高；阅读消息以 15px 为主。mono 仅用于代码、来源编号与可折叠诊断信息。所有字体使用系统本地字体栈，不依赖远程字体请求。

标题最大约 28px，避免挤占长对话。英文文件名允许断行；中文标点与代码各使用相应布局。来源编号不承担主要标题职责。

学习消息中的行内和独立数学公式由共享MessageContent使用KaTeX呈现，公式字体随应用打包，不加载远程字体。独立长公式在自身区域横向滚动；代码中的公式字符保持原样，公式同时提供MathML。

## Layout

桌面以 17rem 侧栏加主内容区。应用固定为视口高度；知识库与会话列表独立滚动，品牌、主导航和底部空间说明保持可见。大纲是独立页，仍使用递归待办列表，不是聊天侧面的固定栏。

对话采用用户指定的 ChatGPT 式滚动结构：紧凑顶部信息、中央对话滚动区、底部固定输入区。消息正文最大 48rem，用户消息靠右，助手回复保持自然阅读宽度。输入框不随长对话移出视口；向上阅读时停止自动跟随，通过“返回最新消息”恢复。资料页和学习准备页在各自面板内滚动，长表单不能被裁掉。所有 grid/flex 高度链显式 min-height:0，不再使用窄屏时把侧栏搬到顶部的布局。

## Elevation & Depth

层级主要依靠间距、标题与边线。普通卡片不叠加大阴影；仅聚焦输入或确有浮层时有必要层次。主内容不使用渐变背景、玻璃模糊或装饰光斑。

## Shapes

控件圆角 10px，细节 6px；聊天输入框 24px 圆角，发送按钮为圆形。助手正文不包卡片，用户消息使用浅灰气泡。重点使用短文字标记而非巨大色块。

## Components

按钮由主色实心、次要描边和低强调文字三类组成，hover/active/focus/disabled/busy 都通过共享 CSS 呈现。busy 固定几何，状态文本在固定区域说明实际阶段。成功只在后端确认后显示。

表单、搜索、资料选择与对话框使用真实 label、input、button、fieldset。表单启用 noValidate，由应用显示校验与恢复动作。可复用反馈由 `components/ui/Feedback.tsx` 拥有；共享字段语义由 `components/ui/Field.tsx` 拥有。原生选择控件只在接受系统弹窗行为时使用；当前来源角色使用原生 radio/checkbox，无自制 listbox。

对话中的测验和复习卡是可阅读产物，不是模仿聊天气泡的 JSON。流式增量只追加到临时消息；最终保存结果替换临时状态。进度提示不显示虚假百分比。手动向上阅读时不强制滚到底部。

图标以少量线条 SVG 为辅，并始终保留文本动作；不使用装饰 emoji 替代标题。动画仅用于实际处理中和轻微颜色过渡；减少动态效果时禁用位移、闪烁与强制平滑滚动。

品牌统一使用已确认的图像生成原稿 `frontend/src/assets/studypilot-pilot-book-logo.png`，由 PilotLogo 展示，CSS 仅收紧原图留白。侧栏知识库名与会话标题单行省略，完整名称保留在 title；目录图标不收缩，展开箭头使用同一枚线条 SVG 旋转。

映射：`colors.* → --color-*`、字体角色 `→ --font-*`、圆角 `→ --radius-*`、spacing `→ --space-*`；布局特殊值保留在拥有该布局的 class。验证脚本检查映射，视觉与行为仍通过浏览器验证。

## Do's and Don'ts

- 展示真实知识点顺序、重点原因、来源与当前状态。
- 在同一会话内保留用户输入、已保存消息和失败恢复入口。
- 不显示尚未测得的准确率、掌握率、节省时间或假进度。
- 不将服务器内部 ID、状态机名称和工具名作为学习者的主要操作语言。

## 2026-09-10 多层待办大纲

每个资料库直接打开一份当前大纲，不显示历史规划任务列表，也不转换旧版卡片大纲。独立大纲页只有标题、可折叠目录、完成状态和短重点标记；没有课件依据、重点依据、长说明或时间估算。LearningOutline 是递归待办列表的唯一组件，使用原生 details/summary 和只读完成框。每个叶子对应独立学习任务，父节点汇总所有后代的完成状态；半选表示部分完成。沿用当前浅色 token 与页面滚动，节点不使用卡片容器。历史会话入口保持按更新时间倒序。按用户本轮要求不执行验证。

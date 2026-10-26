import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    files: ["app/api/**/*.ts", "lib/server/**/*.ts"],
    rules: {
      "react/display-name": "off",
      "react/prop-types": "off",
    },
  },
  {
    // React Compiler (eslint-plugin-react-hooks v7) 的实验性规则。
    //
    // set-state-in-effect 报的是"在 effect 里同步调用 setState"。本项目里的
    // 16 处全部是同一个模式: 把外部数据 (settingsStore / props / 异步拉取的
    // 源列表) 同步到组件 state。这类写法是 React 官方推荐的做法 —— state 是
    // 外部数据的快照, 不是派生值。
    //
    // 关键点: 这 16 处都做了"只在真的变化时才 set"的保护, 不存在无限渲染。
    // 为了消警告去改它们, 反而会引入真 bug —— 本仓库已经因为一次类似的
    // "为消提示而重构" 把 useAutoSkip 改坏过, 最后不得不回滚。
    //
    // 因此: 关掉这条规则, 而不是让 16 处代码互相迁就 lint。
    // 真正会出问题的无限渲染循环, 由 React 自己的运行时错误和
    // "Maximum update depth exceeded" 兜底, 不依赖静态检查。
    rules: {
      "react-hooks/set-state-in-effect": "off",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    ".vercel/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;

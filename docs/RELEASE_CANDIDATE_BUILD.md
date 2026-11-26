# 隔离候选构建

`scripts/build_release_candidate.py` 在新的 `.work/release-build-*` 目录复制实际构建输入，只修改副本里的 Maven reactor 版本。原工作树保持 `4.0.0-SNAPSHOT`。输出与证据路径已存在时会在构建前拒绝，失败不会覆盖历史成功证据。

默认只构建基础插件。需要选定扩展时，重复传入 `--profile`：

```sh
python scripts/build_release_candidate.py --version 4.0.0-rc.5 --java java \
  --profile helstera-bridge \
  --profile craftengine-bridge \
  --profile cookery-bridge
```

仅允许这三种 profile，按固定顺序去重。`cookery-bridge` 会准备其所需的 CraftEngine 编译 API，但不会隐式安装或打包 `StarGenesisCraftEngine` 扩展。是否选择某个扩展不影响其真实运行时依赖：部署者仍须提供对应的实际第三方插件及已验证资源。

可指定新的 `--output-directory` 和 `--evidence-file` 来进行独立重复构建。默认输出 `.work/releases/<version>`，证据为 `docs/evidence/p11/release-candidate-<version>.json`。内存受限主机可显式设置 `--fork-count 0` 和合适的 `MAVEN_OPTS`；证据记录实际选择，不把受限内存测试当作生产性能结论。

## 来源与依赖

源码树哈希覆盖基础模块、显式选择的扩展模块、Maven Wrapper、`.gitattributes`、构建脚本和本次需要的 API 准备脚本/源码锁/补丁。复制时排除旧 `target` 与 Python bytecode。全部所选模块的 reactor parent 版本在隔离副本内统一改写。

构建在所选 JDK 环境下顺序准备真实 API：

| 依赖 | 准备方法 |
|---|---|
| Helstera | 校验固定源码、应用固定清理补丁；运行 API 和 core/runtime/render-paper 相关上游测试 |
| CraftEngine | 校验官方 API JAR/source/POM 的锁定字节，安装独立固定坐标 |
| Cookery | 校验固定 Git blob，在新目录编译未修改上游源码并运行配方测试 |

准备过程不以 Maven 缓存中存在某个坐标作为通过依据。每次实际调用准备脚本，并要求其 `preparation.json` 的 `sourceLockSha256` 与隔离副本的锁文件一致。相关日志、测试报告、依赖清单、准备结果和锁文件复制到输出中的 `dependency-evidence/<provider>/` 并记录逐文件 SHA-256。它证明本次编译输入，不验证生产服务器安装的任意同版本 JAR。

已有精确下载包时，可传 `--dependency-inputs <directory>`，目录结构如下：

```text
helstera.zip             # 锁定提交的 GitHub 源码 ZIP
cookery.zip              # 锁定提交的 GitHub 源码 ZIP
craftengine/             # craftengine-api-lock.json 中列出的全部原始文件
```

只需提供被选择扩展所需的依赖。缓存仅省去源码/发布 API 的重新下载，仍执行原始字节校验、真实源码构建与测试。上游其他 Maven/Gradle 依赖可能仍需网络。

## 验证和产物

Windows 调用 `mvnw.cmd`；POSIX 显式调用 `sh mvnw`，不依赖 checkout 的可执行位。`clean verify` 必须成功，并且每个所选模块都必须有执行过的 Surefire 测试；没有报告、零测试、失败、错误和跳过均拒绝候选产出。

构建检查每个插件的 `plugin.yml` 版本与候选版本一致，有实际 class，且未打包 Helstera、CraftEngine 或 Cookery 的第三方 class。可选桥接还限定只包含自身包中的 class。SBOM 必须为 CycloneDX 1.6。

输出是各自独立的 `StarGenesis-<version>.jar`、`StarGenesisHelstera-<version>.jar`、`StarGenesisCraftEngine-<version>.jar`、`StarGenesisCookery-<version>.jar`，只包含选中的扩展；另附 `sbom.json`、构建日志和依赖证据。证据保留原 `artifact` 基础插件字段，并新增全部 `artifacts`、`profiles`、`modules` 与 `dependencyPreparation` 字段。

两次独立构建需比较源码树、四个所选 JAR 和 SBOM 的 SHA-256；日志路径和耗时不要求相同。真实服务端、第三方运行时、客户端及权限验证必须使用所交付候选的实际字节，并独立记录。候选脚本不会生成正式签名，也不会把自动构建标记成真人、生产容量或运营验收。

已完成的本地三扩展候选为 [RC5](RELEASE_CANDIDATE_RC5.md)：两次各634项测试，四个独立插件包和SBOM逐字节一致；依赖准备、签名/锁与实际Paper/Folia探针的范围和证据分别记录。

后续CI按workflow run number使用`4.0.0-ci.<run number>`，避免新的源码仍生成同名RC5。首次接入候选构建的run `45793130651`保留当时`4.0.0-rc.5`的独立证据；正式候选版本仍由调用者显式指定。

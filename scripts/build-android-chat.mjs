import { existsSync, readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 独立仓库：仓库根目录就是 Android 工程根目录（原先是主仓库下的 android-chat/）。
const project = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const isWindows = process.platform === 'win32'
const javaHome = process.env.JAVA_HOME || (isWindows && existsSync('C:/Program Files/Android/Android Studio/jbr') ? 'C:/Program Files/Android/Android Studio/jbr' : undefined)
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || (isWindows && process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'Android', 'Sdk') : undefined)
const localProperties = join(project, 'local.properties')
if (sdk && existsSync(sdk) && !existsSync(localProperties)) writeFileSync(localProperties, 'sdk.dir=' + sdk.replaceAll('\\', '/').replaceAll(':', '\\:') + '\n')
if (!existsSync(localProperties) && !sdk) throw new Error('Install Android SDK 36 and set ANDROID_HOME, or open this repository in Android Studio.')
const tasks = process.argv.includes('--check') ? [':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug'] : [':app:assembleDebug']
const result = spawnSync(isWindows ? 'gradlew.bat' : 'sh', isWindows ? [...tasks, '--console=plain'] : ['./gradlew', ...tasks, '--console=plain'], {
  cwd: project, stdio: 'inherit', shell: isWindows, windowsHide: true,
  env: { ...process.env, ...(javaHome ? { JAVA_HOME: javaHome } : {}), ...(sdk ? { ANDROID_HOME: sdk } : {}) },
})
if (result.error) throw result.error
if (result.status !== 0) process.exit(result.status ?? 1)
const source = join(project, 'app/build/outputs/apk/debug/app-debug.apk')
const destination = join(project, 'output/ddzhilian-chat-debug.apk')
mkdirSync(dirname(destination), { recursive: true })
copyFileSync(source, destination)
const sha = createHash('sha256').update(readFileSync(destination)).digest('hex')
writeFileSync(destination + '.sha256', sha + '  ddzhilian-chat-debug.apk\n')
console.log('\nNative Liquid Glass chat APK: ' + destination + '\nSHA-256: ' + sha)

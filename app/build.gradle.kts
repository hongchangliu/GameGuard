plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val localMailAddress = providers.environmentVariable("GAMEGUARD_MAIL_ADDRESS").orNull ?: ""
require(localMailAddress.isEmpty() || localMailAddress.matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+")))
android { namespace = "com.gameguard"; compileSdk = 35
 defaultConfig { applicationId = "com.gameguard"; minSdk = 29; targetSdk = 35; versionCode = 19; versionName = "0.18"
  buildConfigField("String", "DEFAULT_MAIL_ADDRESS", "\"$localMailAddress\"") }
 buildFeatures { buildConfig = true }
 buildTypes { getByName("debug") { isDebuggable = false } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }

plugins {
    id("com.android.application")
}

android {
    namespace = "fr.antireels"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.antireels"
        minSdk = 33          // Android 13 minimum ; ton Poco est sous Android 16
        targetSdk = 36
        versionCode = 5
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Version finale : NON débogable (les journaux de diagnostic sont inactifs,
            // l'app n'est pas inspectable via adb).
            isDebuggable = false
            isMinifyEnabled = false
            // Usage personnel : signée avec la clé de débogage locale d'Android Studio.
            // Aucune clé secrète n'est stockée dans le dépôt.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
    }
}

// Volontairement AUCUNE dépendance : moins de code tiers = moins de risques.
dependencies {
}

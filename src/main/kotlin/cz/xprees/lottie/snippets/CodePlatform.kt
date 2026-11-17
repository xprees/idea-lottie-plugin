package cz.xprees.lottie.snippets

/**
 * A runtime that plays Lottie animations in an app, with what the generated code can use: [stateMachines] (dotLottie runtimes only)
 * and [dotLottieFiles] (whether it opens `.lottie` archives; the others play Lottie JSON). [extension] picks the highlighting of the
 * snippets.
 */
enum class CodePlatform(val extension: String, val stateMachines: Boolean, val dotLottieFiles: Boolean, val packageName: String) {
    DOTLOTTIE_WEB("js", true, true, "@lottiefiles/dotlottie-web"),
    DOTLOTTIE_REACT("jsx", true, true, "@lottiefiles/dotlottie-react"),
    LOTTIE_WEB("js", false, false, "lottie-web"),
    REACT_NATIVE("jsx", false, true, "lottie-react-native"),
    DOTLOTTIE_ANDROID_COMPOSE("kt", true, true, "com.lottiefiles:dotlottie-android"),
    DOTLOTTIE_ANDROID_VIEW("kt", true, true, "com.lottiefiles:dotlottie-android"),
    LOTTIE_COMPOSE("kt", false, true, "com.airbnb.android:lottie-compose"),
    LOTTIE_ANDROID("kt", false, true, "com.airbnb.android:lottie"),
    DOTLOTTIE_IOS("swift", true, true, "LottieFiles/dotlottie-ios"),
    LOTTIE_IOS("swift", false, true, "airbnb/lottie-ios"),
    FLUTTER("dart", false, false, "lottie (pub.dev)"),
}

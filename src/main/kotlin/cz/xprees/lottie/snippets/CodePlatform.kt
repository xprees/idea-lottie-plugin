package cz.xprees.lottie.snippets

/**
 * A runtime that plays Lottie animations in an app, with what the generated code can use: [stateMachines] (dotLottie runtimes only)
 * and [dotLottieFiles] (whether it opens `.lottie` archives; the others play Lottie JSON). [extension] picks the highlighting of the
 * snippets.
 */
enum class CodePlatform(val extension: String, val stateMachines: Boolean, val dotLottieFiles: Boolean, val packageName: String) {
    DOTLOTTIE_WEB("js", true, true, "@lottiefiles/dotlottie-web"),
    DOTLOTTIE_REACT("jsx", true, true, "@lottiefiles/dotlottie-react"),
    DOTLOTTIE_VUE("vue", true, true, "@lottiefiles/dotlottie-vue"),
    DOTLOTTIE_SVELTE("svelte", true, true, "@lottiefiles/dotlottie-svelte"),
    DOTLOTTIE_SOLID("jsx", true, true, "@lottiefiles/dotlottie-solid"),
    DOTLOTTIE_WEB_COMPONENT("html", true, true, "@lottiefiles/dotlottie-wc"),
    LOTTIE_WEB("js", false, false, "lottie-web"),
    DOTLOTTIE_REACT_NATIVE("jsx", true, true, "@lottiefiles/dotlottie-react-native"),
    REACT_NATIVE("jsx", false, true, "lottie-react-native"),
    DOTLOTTIE_ANDROID_COMPOSE("kt", true, true, "com.lottiefiles:dotlottie-android"),
    DOTLOTTIE_ANDROID_VIEW("kt", true, true, "com.lottiefiles:dotlottie-android"),
    LOTTIE_COMPOSE("kt", false, true, "com.airbnb.android:lottie-compose"),
    LOTTIE_ANDROID("kt", false, true, "com.airbnb.android:lottie"),
    DOTLOTTIE_IOS("swift", true, true, "LottieFiles/dotlottie-ios"),
    LOTTIE_IOS("swift", false, true, "airbnb/lottie-ios"),
    DOTLOTTIE_FLUTTER("dart", true, true, "dotlottie_flutter (pub.dev)"),
    FLUTTER("dart", false, false, "lottie (pub.dev)"),
}

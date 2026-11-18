package cz.xprees.lottie.snippets

/**
 * A runtime that plays Lottie animations in an app, with what the generated code can use: [stateMachines] (dotLottie runtimes only)
 * and [dotLottieFiles] (whether it opens `.lottie` archives; the others play Lottie JSON). [typeScript]: its code can be written in
 * TypeScript or JavaScript.
 */
enum class CodePlatform(val stateMachines: Boolean, val dotLottieFiles: Boolean, val packageName: String, val typeScript: Boolean = false) {
    DOTLOTTIE_WEB(true, true, "@lottiefiles/dotlottie-web", typeScript = true),
    DOTLOTTIE_REACT(true, true, "@lottiefiles/dotlottie-react", typeScript = true),
    DOTLOTTIE_VUE(true, true, "@lottiefiles/dotlottie-vue", typeScript = true),
    DOTLOTTIE_SVELTE(true, true, "@lottiefiles/dotlottie-svelte", typeScript = true),
    DOTLOTTIE_SOLID(true, true, "@lottiefiles/dotlottie-solid", typeScript = true),
    DOTLOTTIE_WEB_COMPONENT(true, true, "@lottiefiles/dotlottie-wc"),
    LOTTIE_WEB(false, false, "lottie-web", typeScript = true),
    DOTLOTTIE_REACT_NATIVE(true, true, "@lottiefiles/dotlottie-react-native", typeScript = true),
    REACT_NATIVE(false, true, "lottie-react-native", typeScript = true),
    DOTLOTTIE_ANDROID_COMPOSE(true, true, "com.lottiefiles:dotlottie-android"),
    DOTLOTTIE_ANDROID_VIEW(true, true, "com.lottiefiles:dotlottie-android"),
    LOTTIE_COMPOSE(false, true, "com.airbnb.android:lottie-compose"),
    LOTTIE_ANDROID(false, true, "com.airbnb.android:lottie"),
    DOTLOTTIE_IOS(true, true, "LottieFiles/dotlottie-ios"),
    LOTTIE_IOS(false, true, "airbnb/lottie-ios"),
    DOTLOTTIE_FLUTTER(true, true, "dotlottie_flutter (pub.dev)"),
    FLUTTER(false, false, "lottie (pub.dev)"),
}

package cz.xprees.lottie

import java.io.File

object TestData {
    val dir = File("src/test/testData")
    fun text(name: String) = File(dir, name).readText()
    fun bytes(name: String) = File(dir, name).readBytes()
}

package com.github.kotlinisland.inspectionsforlinters

import com.intellij.openapi.projectRoots.Sdk
import java.io.File

fun Sdk.findExecutableInSDK(exec: String): File? {
    val parent = homeDirectory?.parent?.path
    return parent?.let {  File(it, exec) }
}

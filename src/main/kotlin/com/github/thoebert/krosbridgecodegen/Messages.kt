package com.github.thoebert.krosbridgecodegen

data class Type(val className: String, val packageNames: List<String>? = null) {
    override fun toString(): String {
        if (packageNames == null) return className
        return "${packageNames.joinToString("/")}/$className"
    }

    fun copyWithClassSuffix(classSuffix: String): Type {
        return this.copy(className = className + classSuffix, packageNames = packageNames)
    }
}

fun createTypeFromString(packageAndClassName: String): Type {
    val packages = packageAndClassName.split("/")
    return if (packages.size == 1) {
        Type(packageAndClassName)
    } else {
        Type(
            packageNames = packages.take(packages.lastIndex),
            className = packages[packages.lastIndex]
        )
    }
}

data class Field(
    val type: Type,
    val name: String,
    var children: MutableList<Field> = mutableListOf(),
    val value: String? = null,
    val arrayLength: Int = -1
) {

    val isArray: Boolean
        get() = arrayLength >= 0
    val hasArrayLength: Boolean
        get() = arrayLength >= 1
    val isVariable: Boolean
        get() = value == null

    val isComplex: Boolean
        get() = children.isNotEmpty()

    override fun toString(): String {
        return "$type $name" + (if (value != null) "=$value" else "") + (if (children.size != 0) "\n${
            children.joinToString(
                "\n"
            ) { "\t" + it.toString() }
        }\n" else "")
    }
}

abstract class ROSType {
    abstract val name: Type
}

data class Message(override val name: Type, val fields: List<Field>) : ROSType()

data class Service(override val name: Type, val request: List<Field>, val response: List<Field>) : ROSType()

data class Action(override val name: Type, val goal: List<Field>, val result: List<Field>, val feedback: List<Field>) :
    ROSType()
package com.github.thoebert.krosbridgecodegen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.plusParameter
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileFilter

val primitiveTypes = mapOf(
    Type("bool") to BOOLEAN,
    Type("byte") to BYTE,
    Type("char") to CHAR,
    Type(className = "string") to STRING,
    Type("float32") to FLOAT,
    Type("float64") to DOUBLE,
    Type("int8") to BYTE,
    Type("uint8") to SHORT,
    Type("int16") to SHORT,
    Type("uint16") to INT,
    Type("int32") to INT,
    Type("uint32") to LONG,
    Type("int64") to LONG,
    Type("uint64") to LONG,
)

val krosbridgePackageName = "com.github.thoebert.krosbridge"
val messageClassName = ClassName("$krosbridgePackageName.topic", "Message")
val serviceRequestClassName = ClassName("$krosbridgePackageName.service", "ServiceRequest")
val serviceResponseClassName = ClassName("$krosbridgePackageName.service", "ServiceResponse")
val actionGoalClassName = ClassName("$krosbridgePackageName.action", "ActionGoal")
val actionFeedbackClassName = ClassName("$krosbridgePackageName.action", "ActionFeedback")
val actionResultClassName = ClassName("$krosbridgePackageName.action", "ActionResult")
val actionTypeClassName = ClassName("$krosbridgePackageName.action", "ActionType")

val defaultPackageName = "com.github.thoebert.krosbridge.messages"
val defaultPackages = listOf(
    "actionlib_msgs",
    "nav_msgs",
    "shape_msgs",
    "stereo_msgs",
    "diagnostic_msgs",
    "rosgraph_msgs",
    "std_msgs",
    "trajectory_msgs",
    "geometry_msgs",
    "sensor_msgs",
    "std_srvs",
    "visualization_msgs",
)

val requestSuffix = "Request"
val responseSuffix = "Response"
val topicSuffix = "Topic"
val goalSuffix = "Goal"
val resultSuffix = "Result"
val feedbackSuffix = "Feedback"


val serializableAnnotation = ClassName("kotlinx.serialization", "Serializable")

class Writer(val packagePrefix: String = "") {
    fun writeRosType(folder: File, it: ROSType) {
        when (it) {
            is Message -> writeMessage(folder, it)
            is Service -> writeService(folder, it)
            is Action -> writeAction(folder, it)
        }
    }

    private fun writeMessage(folder: File, it: Message) {
        writeTopicClass(folder, it)
        writeClass(folder, it.name, it.fields, messageClassName)
    }

    private fun writeService(folder: File, it: Service) {
        writeServiceClass(folder, it)
        writeClass(folder, it.name.copyWithClassSuffix(requestSuffix), it.request, serviceRequestClassName)
        writeClass(folder, it.name.copyWithClassSuffix(responseSuffix), it.response, serviceResponseClassName)
    }

    private fun writeAction(folder: File, it: Action) {
        writeActionClass(folder, it)
        writeClass(folder, it.name.copyWithClassSuffix(goalSuffix), it.goal, actionGoalClassName)
        writeClass(folder, it.name.copyWithClassSuffix(resultSuffix), it.result, actionResultClassName)
        writeClass(folder, it.name.copyWithClassSuffix(feedbackSuffix), it.feedback, actionFeedbackClassName)
    }

    private fun prefixPackage(packageName: String?): String {
        if (packageName == null) return packagePrefix
        return "$packagePrefix.${packageName}"
    }

    private fun prefixPackage(packageNames: List<String>?): String {

        if (packageNames == null) return packagePrefix
        return "$packagePrefix.${packageNames.joinToString(".")}"
    }

    private fun checkIfTypeExist(type: TypeName, folder: File): Boolean {
        if (primitiveTypes.containsValue(type) || type == LIST) return true
        return folder.listFiles(FileFilter { it.name == "$type.kt" })?.isNotEmpty() ?: false
    }

    private fun writeComplexType(field: Field, folder: File, packageName: List<String>?) {
        if (!field.isComplex) return
        if (checkIfTypeExist(mapType(field, currentPackages = field.type.packageNames), folder)) return
        val classBuilder = TypeSpec.classBuilder(field.type.className)
        classBuilder.addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
        val constructor = FunSpec.constructorBuilder()
        field.children.filter { it.isVariable }.forEach {
            val mappedType = mapType(it, field.type.packageNames)
            constructor.addParameter(it.name, mappedType)
            classBuilder.addProperty(
                PropertySpec.builder(it.name, mappedType)
                    .initializer(it.name).build()
            )
            writeComplexType(it, folder, packageName)
        }
        classBuilder.primaryConstructor(constructor.build())
        writeClassToFile(folder, classBuilder, prefixPackage(packageName), field.type.className)
    }

    private fun writeClass(folder: File, name: Type, fields: List<Field>, parentName: ClassName? = null) {

        val classBuilder = TypeSpec.classBuilder(name.className)
        parentName?.let { classBuilder.superclass(it) }
        classBuilder.addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
        if (fields.isNotEmpty()) classBuilder.addModifiers(KModifier.DATA)

        val constructor = FunSpec.constructorBuilder()
        fields.filter { it.isVariable }.forEach {
            val mappedType = mapType(it, name.packageNames)
            constructor.addParameter(it.name, mappedType)
            classBuilder.addProperty(
                PropertySpec.builder(it.name, mappedType)
                    .initializer(it.name).build()
            )
            writeComplexType(it, folder, name.packageNames)
        }
        classBuilder.primaryConstructor(constructor.build())

        val constants = fields.filter { !it.isVariable }

        if (constants.isNotEmpty()) {
            val companionObject = TypeSpec.companionObjectBuilder()
            constants.forEach {
                companionObject.addProperty(
                    PropertySpec.builder(it.name, mapPrimitiveType(it.type))
                        .mutable(false).initializer(it.value!!).build()
                )
            }
            classBuilder.addType(companionObject.build())
        }

        writeClassToFile(folder, classBuilder, prefixPackage(name.packageNames), name.className)
    }

    private fun writeClassToFile(folder: File, classBuilder: TypeSpec.Builder, packageName: String, className: String) {
        val file = FileSpec.builder(packageName, className)
        file.addType(classBuilder.build())
        file.build().writeTo(folder)


    }

    private fun mapType(field: Field, currentPackages: List<String>?): TypeName {
        if (field.type == Type("Header")) return ClassName(prefixPackage("std_msgs.msg".split(".")), "Header")
        if (field.type == Type("time")) return ClassName(prefixPackage("primitive.msg".split(".")), "Time")
        if (field.type == Type("duration")) return ClassName(prefixPackage("primitive.msg".split(".")), "Duration")
        val baseType = primitiveTypes[field.type] ?: complexType(field, currentPackages)
        println(field)
        println(baseType)

        return if (field.isArray) LIST.parameterizedBy(baseType) else baseType
    }

    private fun mapType(field: Field, currentPackage: String?): TypeName {
        return mapType(field, if (currentPackage != null) listOf(currentPackage) else null)
    }

    private fun mapPrimitiveType(name: Type): ClassName {
        return primitiveTypes[name] ?: throw IllegalArgumentException("Invalid primitive type $name")
    }

    private fun complexType(field: Field, currentPackage: List<String>?): ClassName {
        var packageNames = field.type.packageNames?.toMutableList()
        if (packageNames == null) {
            packageNames = currentPackage?.toMutableList()
        } else if (packageNames.any { defaultPackages.contains(it) }) {
            if (packageNames?.last() != "msg")
                packageNames?.add("msg")
            return ClassName("$defaultPackageName.${packageNames.joinToString(".")}", field.type.className)
        }
        if (packageNames?.last() == "srv") packageNames[packageNames.lastIndex] = "msg"
        else if (packageNames?.last() != "msg")
            packageNames?.add("msg")
        return ClassName(prefixPackage(packageNames), field.type.className)
    }

    private fun writeServiceClass(folder: File, service: Service) {
        val prefixedPackageName = prefixPackage(service.name.packageNames)

        val requestClassName = ClassName(prefixedPackageName, service.name.copyWithClassSuffix(requestSuffix).className)
        val responseClassName =
            ClassName(prefixedPackageName, service.name.copyWithClassSuffix(responseSuffix).className)

        val classBuilder = TypeSpec.classBuilder(service.name.className)

        val constructor = FunSpec.constructorBuilder()
        constructor.addParameter("ros", ClassName(krosbridgePackageName, "Ros"))
        constructor.addParameter("name", String::class)
        classBuilder.primaryConstructor(constructor.build())

        classBuilder.superclass(
            ClassName("$krosbridgePackageName.service", "GenericService")
                .plusParameter(requestClassName)
                .plusParameter(responseClassName)
        ).addSuperclassConstructorParameter("%N", "ros")
            .addSuperclassConstructorParameter("%N", "name")
            .addSuperclassConstructorParameter("%S", service.name)
            .addSuperclassConstructorParameter("%T::class", requestClassName)
            .addSuperclassConstructorParameter("%T::class", responseClassName)


        val requestFn = FunSpec.builder("call")

        requestFn.addModifiers(KModifier.SUSPEND)
        requestFn.returns(
            Pair::class.asClassName()
                .plusParameter(responseClassName.copy(true))
                .plusParameter(Boolean::class)
        )
        val reqParamNames = addParams(service.request, requestFn, service.name.packageNames)
        requestFn.addStatement("return super.call(%T(%L))", requestClassName, reqParamNames)
        classBuilder.addFunction(requestFn.build())

        val sendResponseFn = FunSpec.builder("sendResponse")
        sendResponseFn.addModifiers(KModifier.SUSPEND)
        val respParamNames = addParams(service.response, sendResponseFn, service.name.packageNames)
        sendResponseFn.addParameter("serviceResult", Boolean::class)
        sendResponseFn.addParameter("serviceId", String::class.asClassName().copy(true))
        sendResponseFn.addStatement(
            "return super.sendResponse(%T(%L), serviceResult, serviceId)",
            responseClassName,
            respParamNames
        )
        classBuilder.addFunction(sendResponseFn.build())

        writeClassToFile(folder, classBuilder, prefixedPackageName, service.name.className)
    }

    private fun writeTopicClass(folder: File, message: Message) {
        val prefixedPackageName = prefixPackage(message.name.packageNames)

        val messageClassName = ClassName(prefixedPackageName, message.name.className)
        val topicClassName = ClassName(prefixedPackageName, message.name.copyWithClassSuffix(topicSuffix).className)
        val classBuilder = TypeSpec.classBuilder(topicClassName)

        val constructor = FunSpec.constructorBuilder()
        constructor.addParameter("ros", ClassName(krosbridgePackageName, "Ros"))
        constructor.addParameter("name", String::class)
        classBuilder.primaryConstructor(constructor.build())

        classBuilder.superclass(
            ClassName("$krosbridgePackageName.topic", "GenericTopic")
                .plusParameter(messageClassName)
        ).addSuperclassConstructorParameter("%N", "ros")
            .addSuperclassConstructorParameter("%N", "name")
            .addSuperclassConstructorParameter("%S", message.name)
            .addSuperclassConstructorParameter("%T::class", messageClassName)


        val publishFn = FunSpec.builder("publish")
        publishFn.addModifiers(KModifier.SUSPEND)
        val reqParamNames = addParams(message.fields, publishFn, message.name.packageNames)
        publishFn.addStatement("return super.publish(%T(%L))", messageClassName, reqParamNames)
        classBuilder.addFunction(publishFn.build())

        writeClassToFile(folder, classBuilder, prefixedPackageName, topicClassName.simpleName)
    }

    private fun writeActionClass(folder: File, action: Action) {
        val prefixedPackageName = prefixPackage(action.name.packageNames)

        val goalClassName = ClassName(prefixedPackageName, action.name.copyWithClassSuffix(goalSuffix).className)
        val feedbackClassName =
            ClassName(prefixedPackageName, action.name.copyWithClassSuffix(feedbackSuffix).className)
        val resultClassName = ClassName(prefixedPackageName, action.name.copyWithClassSuffix(resultSuffix).className)


        val classBuilder = TypeSpec.classBuilder(action.name.className)

        val constructor = FunSpec.constructorBuilder()
        constructor.addParameter("ros", ClassName(krosbridgePackageName, "Ros"))
        constructor.addParameter("name", String::class)
        classBuilder.primaryConstructor(constructor.build())

        classBuilder.superclass(
            ClassName("$krosbridgePackageName.action", "GenericAction")
                .plusParameter(goalClassName)
                .plusParameter(feedbackClassName)
                .plusParameter(resultClassName)
        ).addSuperclassConstructorParameter("%N", "ros")
            .addSuperclassConstructorParameter("%N", "name")
            .addSuperclassConstructorParameter("%S", action.name)
            .addSuperclassConstructorParameter("%T::class", goalClassName)
            .addSuperclassConstructorParameter("%T::class", feedbackClassName)
            .addSuperclassConstructorParameter("%T::class", resultClassName)


        val sendGoalFn = FunSpec.builder("sendGoal")

        sendGoalFn.addModifiers(KModifier.SUSPEND)
        sendGoalFn.returns(
            Flow::class.asClassName().plusParameter(
                actionTypeClassName.copy(false)
            )
        )
        sendGoalFn.addParameter("feedback", BOOLEAN)

        val reqParamNames = addParams(action.goal, sendGoalFn, action.name.packageNames)

        sendGoalFn.addStatement("return super.sendGoal(%T(%L), feedback)", goalClassName, reqParamNames)
        classBuilder.addFunction(sendGoalFn.build())

        val sendFeedbackFn = FunSpec.builder("sendFeedback")
        sendFeedbackFn.addModifiers(KModifier.SUSPEND)
        val sendFeedbackParamNames = addParams(action.feedback, sendFeedbackFn, action.name.packageNames)
        sendFeedbackFn.addParameter("id", String::class)
        sendFeedbackFn.addStatement(
            "return super.sendFeedback(%T(%L), id)",
            feedbackClassName,
            sendFeedbackParamNames
        )
        classBuilder.addFunction(sendFeedbackFn.build())


        val sendResultFn = FunSpec.builder("sendResult")
        sendResultFn.addModifiers(KModifier.SUSPEND)
        val sendResultParamNames = addParams(action.result, sendResultFn, action.name.packageNames)
        sendResultFn.addParameter("id", String::class)
        sendResultFn.addParameter(ParameterSpec.builder("isResult", Boolean::class).defaultValue("%L", true).build())
        sendResultFn.addStatement(
            "return super.sendResult(%T(%L), id, isResult)",
            resultClassName,
            sendResultParamNames
        )
        classBuilder.addFunction(sendResultFn.build())

        writeClassToFile(folder, classBuilder, prefixedPackageName, action.name.className)
    }

    private fun addParams(fields: List<Field>, fn: FunSpec.Builder, packageNames: List<String>?): String {
        return fields.filter { it.isVariable }.joinToString { f ->
            val pName = f.name
            fn.addParameter(pName, mapType(f, packageNames))
            pName
        }
    }

}







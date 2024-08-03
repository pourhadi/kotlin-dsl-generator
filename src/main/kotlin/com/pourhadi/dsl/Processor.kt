package com.pourhadi.dsl

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isAnnotationPresent
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.khealth.kona.dsl.annotations.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import java.io.File

class ComponentProcessor(private val environment: SymbolProcessorEnvironment,
                         private val pathOverride: OutputOverride?) : SymbolProcessor {
    var invoked = false
    private fun Resolver.findAnnotations(
        clazz: String,
    ) = getSymbolsWithAnnotation(
        clazz
    )

    private val dslPackage = environment.options["dslPackage"] ?: throw Exception("Need dslPackage")
    private val dslPackageDestination: String by lazy { "${dslPackage}.builders" }

    override fun process(resolver: Resolver): List<KSAnnotated> {

        if (invoked) {
            return emptyList()
        }

        val componentClasses = resolver.findAnnotations(
            DslComponent::class.qualifiedName
                ?: throw Exception("Need annotation name")
        ).filterIsInstance<KSClassDeclaration>().toList()

        val file = FileSpec.builder(dslPackageDestination, "Builders")

        file.addImport(dslPackage, "Component")
        componentClasses.forEach {
            file.addType(generateBuilderClassForComponentClass(it, resolver, componentClasses))
            file.addFunction(generateBuilderFunctionForComponentClass(it))
            generateComponentContainerFunctionsForComponentClass(it, resolver).forEach { file.addFunction(it) }
        }

        pathOverride?.let {
            pathOverride.doneBlock(file.build().writeTo(File(pathOverride.outputPath)).toPath())
        } ?: file.build().writeTo(
            environment.codeGenerator, aggregating = true, originatingKSFiles = resolver.getAllFiles
                ().toList()
        )
        invoked = true
        return emptyList() // we'll return an empty list for now, since we haven't processed anything.
    }

    private fun ClassName.builderName(): String {
        return "${this.simpleName}Builder"
    }

    private fun ClassName.builderType(): ClassName {
        return ClassName(
            packageName = dslPackageDestination,
            simpleNames = listOf(this.builderName())
        )
    }

    private fun KSClassDeclaration.builderName(): String {
        val name = this.simpleName.getShortName()
        return "${name}Builder"
    }

    private fun KSClassDeclaration.builderType(): ClassName {
        return ClassName(
            packageName = dslPackageDestination, simpleNames = listOf(this.builderName())
        )
    }

    /**
     * Generates a builder function for a component class.
     *
     * @param clazz The class declaration of the component.
     * @return The generated [FunSpec] representing the builder function.
     */
    private fun generateBuilderFunctionForComponentClass(clazz: KSClassDeclaration): FunSpec {
        val builderType = clazz.builderType()
        return FunSpec.builder(
            clazz.simpleName.getShortName().replaceFirstChar { it.lowercaseChar() })
            .returns(clazz.toClassName())
            .apply {
                when (clazz.classKind) {
                    ClassKind.OBJECT -> addStatement(
                        "return ${
                            clazz.toClassName().simpleNames.joinToString(
                                "."
                            )
                        }"
                    )

                    else ->
                        addParameter(
                            ParameterSpec(
                                name = "block",
                                type = LambdaTypeName.get(
                                    receiver = builderType,
                                    returnType = ClassName(
                                        packageName = "kotlin",
                                        simpleNames = listOf("Unit")
                                    )
                                )
                            )
                        )
                            .addStatement("return ${clazz.builderName()}().apply(block).build()")
                }
            }
            .build()
    }

    /**
     * Generates a builder class for a component class.
     *
     * @param clazz The class declaration of the component.
     * @param resolver The resolver object used for resolving types and annotations.
     * @return The generated [TypeSpec] representing the builder class.
     */
    @OptIn(KspExperimental::class)
    private fun generateBuilderClassForComponentClass(clazz: KSClassDeclaration, resolver: Resolver, componentClasses: List<KSClassDeclaration>): TypeSpec {
        val builderType = clazz.builderType()
        val builder = TypeSpec.classBuilder(builderType)
        val buildFunctionBuilder = FunSpec.builder("build").returns(clazz.toClassName())
        when (clazz.classKind) {
            ClassKind.ENUM_CLASS -> buildFunctionBuilder.addStatement(
                "return ${
                    clazz.toClassName().simpleNames.joinToString(
                        "."
                    )
                }.entries.first()"
            )

            else -> {
                val paramNames = mutableListOf<String>()
                clazz.primaryConstructor?.parameters?.forEach { param ->
                    val paramName = param.name?.getShortName() ?: throw Exception("no param name")
                    val inPropertyType = param.type.resolve()
                    val defaultValueAnnotation = param.getAnnotationsByType(DslDefaultValue::class).firstOrNull()
                    val hasDefault = defaultValueAnnotation != null
                    val primitive = when (inPropertyType) {
                        resolver.getClassDeclarationByName("Int")?.asType(listOf()),
                        resolver.getClassDeclarationByName("Boolean")?.asType(listOf()),
                        resolver.builtIns.intType,
                        resolver.builtIns.floatType,
                        resolver.builtIns.longType,
                        resolver.builtIns.booleanType -> true

                        else -> false
                    }

                    val outPropertyType =
                        if (primitive && !hasDefault) inPropertyType.makeNullable() else inPropertyType

                    builder.addProperty(
                        PropertySpec.builder(
                            paramName,
                            outPropertyType.toTypeName()
                        )
                            .mutable()
                            .apply {
                                if (!outPropertyType.isMarkedNullable && !hasDefault) {
                                    addModifiers(KModifier.LATEINIT)
                                } else {
                                    defaultValueAnnotation?.let {
                                        initializer(defaultValueAnnotation.value)
                                    } ?: initializer("null")
                                }
                            }
                            .build()
                    )

                    val isComponentParam = inPropertyType.declaration is KSClassDeclaration &&
                            componentClasses.firstOrNull { it.toClassName().canonicalName == inPropertyType.toClassName().canonicalName }?.isAnnotationPresent(DslComponent::class) == true
                    if (isComponentParam) {
                        builder.addFunction(
                            FunSpec.builder(paramName)
                                .returns(inPropertyType.toClassName())
                                .addParameter(
                                    ParameterSpec(
                                        name = "block",
                                        type = LambdaTypeName.get(
                                            receiver = inPropertyType.toClassName().builderType(),
                                            returnType = ClassName(
                                                packageName = "kotlin",
                                                simpleNames = listOf("Unit")
                                            )
                                        )
                                    )
                                )
                                .addStatement(
                                    "val item = ${
                                        inPropertyType.toClassName().builderName()
                                    }().apply(block).build()"
                                )
                                .addStatement("$paramName = item")
                                .addStatement("return item")
                                .build()
                        )
                    }

                    val throwIfNull = if (primitive && !hasDefault) "!!" else ""
                    paramNames.add("${paramName}$throwIfNull")
                }

                buildFunctionBuilder.addStatement(
                    "return ${
                        clazz.toClassName().simpleNames.joinToString(
                            "."
                        )
                    }(${paramNames.joinToString()})"
                )
            }
        }

        if (clazz.classKind != ClassKind.OBJECT) {
            builder.addFunction(buildFunctionBuilder.build())
        }

        if (clazz.isAnnotationPresent(DslScope::class)) {
            builder.addAnnotation(DslScope::class)
        }

        if (clazz.isAnnotationPresent(DslContainer::class)) {
            val itemsName: String =
                clazz.primaryConstructor?.parameters?.firstOrNull { it.isAnnotationPresent(DslContainerField::class) }?.name?.getShortName()
                    ?: throw Exception("@DslContainer does not have a @DslContainerField")
            builder.addFunction(FunSpec
                .builder("add")
                .addParameter(
                    name = "component",
                    type =   ClassName(
                        packageName = dslPackage,
                        simpleNames = listOf("Component", "View")
                    ).copy(),
                    modifiers = listOf()
                ).addStatement(
                    "$itemsName += listOf(component)"
                ).build())

            builder.addFunction(
                FunSpec.builder("item")
                    .addParameter(
                        name = "block",
                        type = LambdaTypeName.get(
                            parameters = listOf(),
                            returnType = ClassName(
                                packageName = dslPackage,
                                simpleNames = listOf("Component", "View")
                            )
                        )
                    )
                    .addStatement("$itemsName += listOf(block())")
                    .build()
            )
        }

        return builder.build()
    }

    /**
     * Generates component container functions for a given component class.
     *
     * @param clazz The class declaration of the component.
     * @return The list of generated [FunSpec] representing the component container functions.
     */
    @OptIn(KspExperimental::class)
    private fun generateComponentContainerFunctionsForComponentClass(clazz: KSClassDeclaration, resolver: Resolver): List<FunSpec> {
        val builderType = clazz.builderType()

        val containers = resolver.findAnnotations(
            DslContainer::class.qualifiedName
                ?: throw Exception("Need annotation name")
        )
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                it.toClassName().builderName() != clazz.builderName()
                        && clazz.superTypes
                    .any { type ->
                        type.toTypeName() == ClassName(
                            packageName = dslPackage,
                            simpleNames = listOf("Component", "View")
                        ) || (type.toTypeName() as? ParameterizedTypeName)?.rawType == ClassName(
                            packageName = dslPackage,
                            simpleNames = listOf("Input")
                        )
                    }
            }
            .toList()

        return containers.map {
            val className = ClassName(packageName = dslPackage, listOf(it.toClassName().simpleName)).builderType()
            val itemsName: String =
                it.primaryConstructor?.parameters?.firstOrNull { it.isAnnotationPresent(DslContainerField::class) }?.name?.getShortName()
                    ?: throw Exception("@DslContainer does not have a @DslContainerField")
            FunSpec.builder(
                clazz.simpleName.getShortName().replaceFirstChar { char -> char.lowercaseChar() })
                .receiver(className)
                .returns(clazz.toClassName())

                .apply {
                    when (clazz.classKind) {
                        ClassKind.OBJECT ->
                            addStatement(
                                "val item = ${
                                    clazz.toClassName().simpleNames.joinToString(
                                        "."
                                    )
                                }"
                            )
                                .addStatement("$itemsName += listOf(item)")
                                .addStatement("return item")
                        else ->
                            addParameter(
                                ParameterSpec(
                                    name = "block",
                                    type = LambdaTypeName.get(
                                        receiver = builderType,
                                        returnType = ClassName(
                                            packageName = "kotlin",
                                            simpleNames = listOf("Unit")
                                        )
                                    )
                                )
                            )
                                .addStatement("val item = ${clazz.builderName()}().apply(block).build()")
                                .addStatement("$itemsName += listOf(item)")
                                .addStatement("return item")
                    }
                }
                .build()
        }
    }
}
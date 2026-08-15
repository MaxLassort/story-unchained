package com.maxlass.studio.infrastructure.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.GenericHttpMessageConverter
import java.io.IOException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.charset.StandardCharsets

/**
 * HTTP message converter backed by kotlinx.serialization for DTOs annotated `@Serializable`.
 * Garantit la parité JSON avec le serveur Ktor (`prettyPrint` + `encodeDefaults`) sans passer par Jackson.
 */
class KotlinxJsonHttpMessageConverter(
    private val json: Json,
) : GenericHttpMessageConverter<Any> {

    private fun serializerFor(type: Type): KSerializer<Any> = json.serializersModule.serializer(type)

    /** True if [type] resolves to a kotlinx serializer (supports generic types like `List<Pack>`). */
    private fun canHandle(type: Type): Boolean =
        runCatching { serializerFor(type) }.isSuccess &&
            !type.asClass().isArray

    private fun isSerializable(clazz: Class<*>): Boolean =
        !clazz.isArray && clazz.isAnnotationPresent(Serializable::class.java)

    private fun isGenericSerializable(type: Type, clazz: Class<*>): Boolean =
        canHandle(type) || isSerializable(clazz)

    override fun canRead(clazz: Class<*>, mediaType: MediaType?): Boolean =
        isSerializable(clazz) && mediaType?.includes(MediaType.APPLICATION_JSON) != false

    override fun canRead(type: Type, contextClass: Class<*>?, mediaType: MediaType?): Boolean =
        mediaType?.includes(MediaType.APPLICATION_JSON) != false &&
            isGenericSerializable(type, type.asClass())

    override fun canWrite(clazz: Class<*>, mediaType: MediaType?): Boolean =
        isSerializable(clazz) && mediaType?.includes(MediaType.APPLICATION_JSON) != false

    override fun canWrite(type: Type?, clazz: Class<*>, mediaType: MediaType?): Boolean =
        mediaType?.includes(MediaType.APPLICATION_JSON) != false &&
            isGenericSerializable(type ?: clazz, clazz)

    override fun read(clazz: Class<out Any>, inputMessage: HttpInputMessage): Any =
        read(clazz, clazz, inputMessage)

    override fun read(type: Type, contextClass: Class<*>?, inputMessage: HttpInputMessage): Any {
        val body = inputMessage.body.readBytes().decodeToString()
        return json.decodeFromString(serializerFor(type), body)
    }

    override fun write(t: Any, contentType: MediaType?, outputMessage: HttpOutputMessage) =
        write(t, t.javaClass, contentType, outputMessage)

    @Throws(IOException::class)
    override fun write(t: Any, type: Type?, contentType: MediaType?, outputMessage: HttpOutputMessage) {
        val serializer = runCatching { serializerFor(type ?: t.javaClass) }
            .getOrElse { serializerFor(t.javaClass) }
        val bytes = json.encodeToString(serializer, t).toByteArray(StandardCharsets.UTF_8)
        outputMessage.headers.contentType = contentType ?: MediaType.APPLICATION_JSON
        outputMessage.body.buffered().use { it.write(bytes) }
    }

    override fun getSupportedMediaTypes(): List<MediaType> = listOf(MediaType.APPLICATION_JSON)

    private fun Type.asClass(): Class<*> =
        when (this) {
            is Class<*> -> this
            is ParameterizedType -> rawType as? Class<*> ?: Any::class.java
            is WildcardType -> (upperBounds.firstOrNull() as? Class<*>) ?: Any::class.java
            else -> Any::class.java
        }
}

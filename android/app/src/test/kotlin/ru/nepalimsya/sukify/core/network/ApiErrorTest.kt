package ru.nepalimsya.sukify.core.network

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {

    @Test
    fun validationErrorsAreTranslatedPerField() {
        val body = """{"message":"The email has already been taken.","errors":{"email":["The email has already been taken."],"password":["The password field confirmation does not match."]}}"""
        val error = ApiError.fromHttp(422, body)

        assertEquals(422, error.code)
        assertEquals("Эта почта уже зарегистрирована", error.fields["email"])
        assertEquals("Пароли не совпадают", error.fields["password"])
        assertEquals("Эта почта уже зарегистрирована", error.message)
    }

    @Test
    fun russianServerMessagesPassThrough() {
        val body = """{"message":"Неверная почта или пароль.","errors":{"email":["Неверная почта или пароль."]}}"""
        assertEquals("Неверная почта или пароль.", ApiError.fromHttp(422, body).message)
    }

    @Test
    fun statusCodesGetHumanMessages() {
        assertEquals("Сессия истекла — войди заново", ApiError.fromHttp(401, null).message)
        assertEquals("Аккаунт заблокирован", ApiError.fromHttp(403, """{"message":"Your account has been suspended."}""").message)
        assertEquals("Слишком много попыток — подожди минуту", ApiError.fromHttp(429, "Too Many Attempts.").message)
        assertEquals("Сервер не отвечает, попробуй позже", ApiError.fromHttp(502, "<html>").message)
    }

    @Test
    fun networkFailureIsRecognised() {
        val error = ApiError.from(IOException("timeout"))
        assertTrue(error.isNetwork)
        assertTrue(error.message.startsWith("Нет связи"))
    }
}

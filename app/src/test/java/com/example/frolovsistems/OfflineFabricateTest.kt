package com.example.frolovsistems

import com.example.frolovsistems.core.offline.QueuedWrite
import com.example.frolovsistems.core.offline.OfflineFabricate
import com.example.frolovsistems.core.offline.UploadSpec
import com.example.frolovsistems.core.offline.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор multipart-форм и перемапливание локальных номеров — узкие места
 * офлайн-очереди. Формы собираются так же, как их собирает OkHttp при
 * загрузке снимков и файлов архива.
 */
class OfflineFabricateTest {

    @Test
    fun `parseMultipart вынимает файл и текстовые поля`() {
        val boundary = "----okhttp123"
        val image = byteArrayOf(-119, 80, 78, 71, 1, 2, 3) // условный бинар
        val form = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"photo\"; filename=\"snap.jpg\"\r\n")
            append("Content-Type: image/jpeg\r\n\r\n")
        }.encodeToByteArray() +
            image + "\r\n".encodeToByteArray() +
            buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                append("Щит в коридоре\r\n")
                append("--$boundary--\r\n")
            }.encodeToByteArray()

        val parsed = OfflineFabricate.parseMultipart(
            "multipart/form-data; boundary=$boundary",
            form,
        )

        assertNotNull(parsed)
        assertEquals("photo", parsed!!.fileField)
        assertEquals("snap.jpg", parsed.fileName)
        assertEquals("image/jpeg", parsed.mime)
        assertTrue(parsed.bytes.contentEquals(image))
        assertEquals("Щит в коридоре", parsed.fields["caption"])
    }

    @Test
    fun `remap меняет локальные номера в пути и в полях-ссылках`() {
        val op = QueuedWrite(
            id = 1,
            method = "PUT",
            path = "/api/v1/admin/orders/-3",
            body = """{"clientId":-2,"title":"Щитовая","priceKop":1500000,"workerId":7}""",
            queuedAt = 0L,
        )
        val (path, body) = OfflineFabricate.remap(op, mapOf(-2L to 41L, -3L to 77L))

        assertEquals("/api/v1/admin/orders/77", path)
        assertTrue(body!!.contains("\"clientId\":41"))
        assertTrue(body.contains("\"workerId\":7")) // настоящий номер не трогаем
    }

    @Test
    fun `remapUpload подставляет настоящий номер папки`() {
        val spec = UploadSpec(
            path = "/api/v1/admin/files",
            fileField = "file",
            fileName = "act.pdf",
            mime = "application/pdf",
            fields = mapOf("name" to "act.pdf", "folderId" to "-5"),
        )
        val remapped = OfflineFabricate.remapUpload(spec, mapOf(-5L to 12L))
        assertEquals("12", remapped.fields["folderId"])
        assertEquals("act.pdf", remapped.fields["name"])
    }

    @Test
    fun `describe различает создание правку и загрузку`() {
        assertEquals(
            "Создание: клиент",
            QueuedWrite(id = 1, method = "POST", path = "/api/v1/admin/clients", body = "{}", queuedAt = 0).describe(),
        )
        assertEquals(
            "Удаление: заказ №5",
            QueuedWrite(id = 2, method = "DELETE", path = "/api/v1/admin/orders/5", queuedAt = 0).describe(),
        )
        assertEquals(
            "Загрузка: фото заказа",
            QueuedWrite(
                id = 3,
                kind = QueuedWrite.KIND_UPLOAD,
                method = "POST",
                path = "/api/v1/admin/orders/5/photos",
                upload = UploadSpec("/api/v1/admin/orders/5/photos", "photo", "s.jpg", "image/jpeg"),
                queuedAt = 0,
            ).describe(),
        )
    }
}

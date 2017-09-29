package com.qiscus.sdk.chat.data.remote

import com.qiscus.sdk.chat.data.source.account.AccountLocal
import com.qiscus.sdk.chat.data.source.file.FileRemote
import com.qiscus.sdk.chat.data.source.file.ProgressListener
import com.qiscus.sdk.chat.data.util.FilePathGenerator
import io.reactivex.Single
import okhttp3.*
import okhttp3.internal.Util
import okio.BufferedSink
import okio.Okio
import okio.Source
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Created on : September 26, 2017
 * Author     : zetbaitsu
 * Name       : Zetra
 * GitHub     : https://github.com/zetbaitsu
 */
class FileRemoteImpl(private val accountLocal: AccountLocal,
                     private val baseUrl: String,
                     private val httpClient: OkHttpClient,
                     private val filePathGenerator: FilePathGenerator) : FileRemote {

    override fun upload(file: File, progressListener: ProgressListener): Single<String> {
        return Single.defer {
            Single.fromCallable {
                val fileLength = file.length()

                val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("token", accountLocal.getAccount().token)
                        .addFormDataPart("file", file.name,
                                CountingFileRequestBody(file, object : ProgressListener {
                                    override fun onProgress(total: Int) {
                                        val progress = total * 100 / fileLength
                                        progressListener.onProgress(progress.toInt())
                                    }
                                }))
                        .build()

                val request = Request.Builder()
                        .url(baseUrl + "/api/v2/mobile/upload")
                        .post(requestBody).build()
                val response = httpClient.newCall(request).execute()
                val json = JSONObject(response.body()!!.string())
                return@fromCallable json.getJSONObject("results").getJSONObject("file").getString("url")
            }
        }
    }

    override fun download(attachmentUrl: String, progressListener: ProgressListener): Single<File> {
        return Single.defer {
            Single.fromCallable {
                val inputStream: InputStream?
                val fos: FileOutputStream?

                val request = Request.Builder().url(attachmentUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val responseBody = response.body()
                    if (responseBody != null) {
                        throw HttpException(Response.error<Any>(response.code(), responseBody))
                    }
                    throw IOException()
                }

                val output = File(filePathGenerator.generateFilePath(attachmentUrl))
                fos = FileOutputStream(output.path)

                val responseBody = response.body()
                val fileLength = responseBody!!.contentLength()

                inputStream = responseBody.byteStream()
                val buffer = ByteArray(4096)
                var total: Long = 0
                var count: Int = inputStream.read(buffer)
                while (count != -1) {
                    total += count.toLong()
                    val totalCurrent = total
                    if (fileLength > 0) {
                        progressListener.onProgress((totalCurrent * 100 / fileLength).toInt())
                    }
                    fos.write(buffer, 0, count)
                    count = inputStream.read(buffer)
                }
                fos.flush()
                fos.close()
                inputStream?.close()
                return@fromCallable output
            }
        }
    }

    private class CountingFileRequestBody(private val file: File, private val progressListener: ProgressListener) : RequestBody() {

        override fun contentType(): MediaType? {
            return MediaType.parse("application/octet-stream")
        }

        @Throws(IOException::class)
        override fun contentLength(): Long {
            return file.length()
        }

        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
            var source: Source? = null
            try {
                source = Okio.source(file)
                var total: Long = 0
                var read: Long = source!!.read(sink.buffer(), SEGMENT_SIZE)

                while (read != -1L) {
                    total += read
                    sink.flush()
                    progressListener.onProgress(total.toInt())
                    read = source.read(sink.buffer(), SEGMENT_SIZE)

                }
            } finally {
                Util.closeQuietly(source)
            }
        }

        companion object {
            private val SEGMENT_SIZE = 2048L
        }

    }
}
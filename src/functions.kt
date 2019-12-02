package net.opens3

import com.google.gson.*
import io.ktor.client.features.json.GsonSerializer
import io.ktor.html.insert
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.json.JSONObject

fun connectToDB(): Unit {
    Database.connect(
        "jdbc:mysql://${DB_ADDRESS}/${DB_NAME}?useSSL=false",
        "com.mysql.jdbc.Driver",
        user = DB_USERNAME,
        password = DB_PASSWORD
    )
}

fun retrieveToken(): String {
    var ourToken: String = "NULL"
    connectToDB()
    transaction {
        SchemaUtils.create(APIToken)
        var tokenId = APIToken.selectAll()
        for (token in tokenId) {
            ourToken = token[APIToken.accessToken]
        }
    }
    return ourToken
}

fun retrieveNumber(): String {
    var ourNumber: String = "NULL"
    connectToDB()
    transaction {
        SchemaUtils.create(ProvisionedNumber)
        var tokenId = ProvisionedNumber.selectAll()
        for (token in tokenId) {
            ourNumber = token[ProvisionedNumber.destinationAddress]
        }
    }
    return ourNumber
}

fun provisionNumer(): Unit {
    runBlocking {
        var token = retrieveToken();

        val response = khttp.post(
            url = "https://tapi.telstra.com/v2/messages/provisioning/subscriptions",
            //url = "http://127.0.0.1:8080/foo",
            headers = mapOf(
                "content-type" to "application/json",
                "authorization" to "Bearer ${token}",
                "cache-control" to "no-cache"
            ),
            data = "{ \"activeDays\": 30 }"
        )
        println(response)
        if (response.statusCode == 201) {
            val obj: JSONObject = response.jsonObject
            println(obj)
            connectToDB()
            transaction {
                SchemaUtils.create(ProvisionedNumber)
                ProvisionedNumber.deleteAll()
                var provisionedNumberId = ProvisionedNumber.insert {
                    it[destinationAddress] = obj["destinationAddress"].toString()
                    it[expiryDate] = obj["expiryDate"].toString()
                }
            }
        }
    }
}

fun updateToken(): Unit {
    runBlocking {
        for (i in 0..3) {
            println("Update attempt ${i}")
            try {
                val payload = mapOf(
                    "grant_type" to "client_credentials",
                    "scope" to "NSMS",
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET
                )

                val response = khttp.post(
                    url = " https://tapi.telstra.com/v2/oauth/token",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = payload
                )

                if (response.statusCode == 200) {
                    println("Success")
                    val obj: JSONObject = response.jsonObject
                    connectToDB()
                    transaction {
                        SchemaUtils.create(APIToken)
                        APIToken.deleteAll()
                        var tokenId = APIToken.insert {
                            it[accessToken] = obj["access_token"].toString()
                            it[expiresIn] = obj["expires_in"].toString()
                        }
                        println("Token added with id: ${tokenId[APIToken.id]}")
                    }
                    break
                } else {
                    println(response.statusCode)
                    println(response.text)
                }
            } catch (e: Error) {
                println(e)
            }
        }
    }
}

fun getMSGS(): Boolean {
    var token = retrieveToken()
    try {
        val response = khttp.get(
            url = " https://tapi.telstra.com/v2/messages/sms",
            headers = mapOf("content-type" to "application/json", "authorization" to "Bearer ${token}")
        )
        if (response.statusCode == 201) {
            println("Success")
            println(response.jsonObject)
        } else if (response.statusCode == 401) {
            println("token expired, updating...")
            updateToken()
        } else if (response.statusCode == 400) {
            println("number not provisioned, provisioning....")
            provisionNumer()
        } else if (response.statusCode == 200) {
            println(response.statusCode)
            println(response.jsonObject)
            val gson = Gson()
            try {
                var msg = gson.fromJson(response.text, MessageObject::class.java)
                println(msg)
                if (msg.status == "EMPTY") {
                    println("No more messages.")
                    return false
                }
                transaction {
                    SchemaUtils.create(Message)
                    Message.insert {
                        it[apiMsgId] = msg.apiMsgId
                        it[destinationAddress] = msg.destinationAddress
                        it[message] = msg.message
                        it[messageId] = msg.messageId
                        it[senderAddress] = msg.senderAddress
                        it[sentTimestamp] = DateTime(msg.sentTimestamp)
                        it[status] = msg.status
                    }
                }
                return true

            } catch (e: Error) {
                println("Could not parse message object.")
            }
        } else {
            println("Unknown response code.")
        }
    } catch (e: Error) {
        println(e)
    }
    return false
}

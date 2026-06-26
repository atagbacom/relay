package com.example.relay

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
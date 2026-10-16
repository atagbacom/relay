package com.relay.app.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Format binaire des paquets Relay, à la place de la sérialisation Java.
 *
 * Les octets arrivent de n'importe quel appareil à portée : on ne
 * reconstruit jamais d'objets arbitraires, on lit des champs de taille
 * bornée, et tout paquet mal formé est rejeté.
 *
 * Version 1 (entiers en big-endian) :
 *
 *   octets  champ
 *   2       magique "RL"
 *   1       version (1)
 *   4       taille totale du paquet, en-tête compris
 *   1       type (1 message, 2 clés, 3 session différée, 4 accusé)
 *   1       ttl (0 à MAX_HOPS)
 *   8       createdAt (ms)
 *   8       expiresAt (ms)
 *   1 + n   id (UTF-8, 1 à 64 octets)
 *   1 + n   senderId (UTF-8, 1 à 32 octets)
 *   1 + n   recipientId (UTF-8, 0 à 32 octets ; 0 = diffusion)
 *   2 + n   signature (0 à 128 octets)
 *   4 + n   payload (0 à MAX_PAYLOAD octets)
 */
object PacketCodec {

    const val VERSION = 1
    const val TAILLE_EN_TETE = 7               // magique + version + taille totale
    const val MAX_PAYLOAD = 16 * 1024
    const val MAX_TAILLE = 18 * 1024

    private const val MAGIQUE_1 = 0x52          // 'R'
    private const val MAGIQUE_2 = 0x4C          // 'L'
    private const val MAX_ID = 64
    private const val MAX_SHORT_ID = 32
    private const val MAX_SIGNATURE = 128

    private val CODES = mapOf(
        PacketType.MESSAGE to 1,
        PacketType.PREKEY_GOSSIP to 2,
        PacketType.DEFERRED_INIT to 3,
        PacketType.ACK to 4,
        PacketType.VERIFICATION to 5
    )
    private val TYPES = CODES.entries.associate { (type, code) -> code to type }

    fun encode(packet: RelayPacket): ByteArray {
        val id = packet.id.toByteArray(Charsets.UTF_8)
        val sender = packet.senderId.toByteArray(Charsets.UTF_8)
        val recipient = packet.recipientId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

        require(id.size in 1..MAX_ID) { "id invalide" }
        require(sender.size in 1..MAX_SHORT_ID) { "senderId invalide" }
        require(packet.recipientId == null || recipient.size in 1..MAX_SHORT_ID) { "recipientId invalide" }
        require(packet.signature.size <= MAX_SIGNATURE) { "signature trop longue" }
        require(packet.payload.size <= MAX_PAYLOAD) { "payload trop long" }
        require(packet.ttl in 0..RelayPacket.MAX_HOPS) { "ttl invalide" }

        val corps = ByteArrayOutputStream()
        DataOutputStream(corps).use { out ->
            out.writeByte(CODES.getValue(packet.type))
            out.writeByte(packet.ttl)
            out.writeLong(packet.createdAt)
            out.writeLong(packet.expiresAt)
            out.writeByte(id.size); out.write(id)
            out.writeByte(sender.size); out.write(sender)
            out.writeByte(recipient.size); out.write(recipient)
            out.writeShort(packet.signature.size); out.write(packet.signature)
            out.writeInt(packet.payload.size); out.write(packet.payload)
        }
        val octetsCorps = corps.toByteArray()

        val tout = ByteArrayOutputStream(TAILLE_EN_TETE + octetsCorps.size)
        DataOutputStream(tout).use { out ->
            out.writeByte(MAGIQUE_1)
            out.writeByte(MAGIQUE_2)
            out.writeByte(VERSION)
            out.writeInt(TAILLE_EN_TETE + octetsCorps.size)
            out.write(octetsCorps)
        }
        return tout.toByteArray()
    }

    /**
     * Taille totale annoncée par un début de paquet, ou null si ce début
     * n'est pas un en-tête Relay valide. Sert à savoir quand un paquet
     * reçu en plusieurs morceaux est complet.
     */
    fun tailleAnnoncee(debut: ByteArray): Int? {
        if (debut.size < TAILLE_EN_TETE) return null
        if (debut[0].toInt() != MAGIQUE_1 || debut[1].toInt() != MAGIQUE_2) return null
        if (debut[2].toInt() != VERSION) return null
        val taille = ((debut[3].toInt() and 0xFF) shl 24) or
            ((debut[4].toInt() and 0xFF) shl 16) or
            ((debut[5].toInt() and 0xFF) shl 8) or
            (debut[6].toInt() and 0xFF)
        return taille.takeIf { it in TAILLE_EN_TETE + 1..MAX_TAILLE }
    }

    /** Le paquet décodé, ou null s'il est mal formé, tronqué, trop grand ou d'une autre version. */
    fun decode(bytes: ByteArray): RelayPacket? {
        if (bytes.size > MAX_TAILLE) return null
        if (tailleAnnoncee(bytes) != bytes.size) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes, TAILLE_EN_TETE, bytes.size - TAILLE_EN_TETE)).use { inp ->
                val type = TYPES[inp.readUnsignedByte()] ?: return null
                val ttl = inp.readUnsignedByte()
                if (ttl > RelayPacket.MAX_HOPS) return null
                val createdAt = inp.readLong()
                val expiresAt = inp.readLong()
                val id = lireTexte(inp, 1, MAX_ID) ?: return null
                val sender = lireTexte(inp, 1, MAX_SHORT_ID) ?: return null
                val recipient = lireTexte(inp, 0, MAX_SHORT_ID) ?: return null
                val signature = lireOctets(inp, inp.readUnsignedShort(), MAX_SIGNATURE) ?: return null
                val payload = lireOctets(inp, inp.readInt(), MAX_PAYLOAD) ?: return null
                if (inp.available() != 0) return null      // octets en trop

                RelayPacket(
                    id = id,
                    type = type,
                    senderId = sender,
                    recipientId = recipient.ifEmpty { null },
                    payload = payload,
                    signature = signature,
                    ttl = ttl,
                    createdAt = createdAt,
                    expiresAt = expiresAt
                )
            }
        } catch (e: EOFException) {
            null
        }
    }

    private fun lireTexte(inp: DataInputStream, min: Int, max: Int): String? {
        val n = inp.readUnsignedByte()
        if (n < min || n > max) return null
        return String(lireOctets(inp, n, max) ?: return null, Charsets.UTF_8)
    }

    private fun lireOctets(inp: DataInputStream, n: Int, max: Int): ByteArray? {
        if (n < 0 || n > max) return null
        return ByteArray(n).also { inp.readFully(it) }
    }
}

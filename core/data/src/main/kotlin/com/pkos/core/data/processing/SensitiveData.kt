package com.pkos.core.data.processing

/**
 * Spec 4.1 step 13 / 4.6 / 10: before anything goes to a cloud AI, look for secrets a person would not want to send:
 * card numbers (Luhn-checked), one-time codes, passwords/PINs, private keys and API keys, Bangladeshi NID and bank
 * account numbers. Detection is local and pattern-based; a hit keeps the item on the phone unless the owner has
 * explicitly allowed cloud for that item.
 */
object SensitiveData {
    enum class Kind { CARD, OTP, PASSWORD, SECRET_KEY, NATIONAL_ID, BANK_ACCOUNT }

    private val cardLike = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val otp = Regex("(?i)(otp|one[- ]time|verification code|security code|পিন|ওটিপি|কোড)\\D{0,25}\\d{4,8}(?!\\d)")
    private val password = Regex("(?i)(password|passcode|passwd|pwd|pin|পাসওয়ার্ড|পাসওয়ার্ড|গোপন নম্বর)\\s*[:=：]\\s*\\S{3,}")
    private val privateKey = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")
    private val apiKey = Regex("\\b(AIza[0-9A-Za-z_\\-]{30,}|sk-[A-Za-z0-9_\\-]{20,}|ghp_[A-Za-z0-9]{30,}|ntn_[A-Za-z0-9]{30,}|secret_[A-Za-z0-9]{30,}|xox[abp]-[A-Za-z0-9\\-]{10,})")
    private val nid = Regex("(?i)(nid|national id|জাতীয় পরিচয়|এনআইডি)\\D{0,20}(\\d{10}|\\d{13}|\\d{17})(?!\\d)")
    private val bank = Regex("(?i)(a/c|acc(ount)?\\s*(no|number|#)|হিসাব নম্বর|অ্যাকাউন্ট নম্বর)\\D{0,10}\\d{8,20}")

    fun find(text: String): Set<Kind> = buildSet {
        if (cardLike.findAll(text).any { luhn(it.value.filter(Char::isDigit)) }) add(Kind.CARD)
        if (otp.containsMatchIn(text)) add(Kind.OTP)
        if (password.containsMatchIn(text)) add(Kind.PASSWORD)
        if (privateKey.containsMatchIn(text) || apiKey.containsMatchIn(text)) add(Kind.SECRET_KEY)
        if (nid.containsMatchIn(text)) add(Kind.NATIONAL_ID)
        if (bank.containsMatchIn(text)) add(Kind.BANK_ACCOUNT)
    }

    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.toSet().size == 1) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}

/** Offline keyword picker for the local engine: frequent meaningful words that are not already tags. */
object LocalKeywords {
    private val stop = setOf(
        "the", "and", "for", "with", "this", "that", "from", "your", "you", "are", "was", "have", "has", "will", "can", "how", "what",
        "into", "about", "more", "http", "https", "www", "com", "এবং", "একটি", "এই", "করে", "করা", "হয়", "থেকে", "জন্য", "আমি", "আমার", "তার", "যে", "না",
    )
    fun pick(text: String, exclude: Collection<String>, max: Int = 8): List<String> {
        val ex = exclude.map { it.lowercase() }.toSet()
        return Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(text.lowercase()).map { it.value }
            .filter { it.length >= 4 && it !in stop && it !in ex && !it.all(Char::isDigit) }
            .groupingBy { it }.eachCount().entries.filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(max).map { it.key }
    }
}

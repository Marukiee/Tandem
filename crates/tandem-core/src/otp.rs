//! Finding a one-time verification code in the text of a message or a notification.
//!
//! A pure function on purpose: the Mac, the phone and Windows all call this one, so a code is
//! recognised the same way everywhere, and it can be tested without any device. It is scanned by
//! hand instead of with a regex crate, because the core has no regex dependency and this runs for
//! every notification that arrives.
//!
//! A number only counts as a code when it sits near a word that says so ("code", "verificatie",
//! "OTP", the name of a bank). A bare number is far more often an amount, a date, a phone number
//! or an order number than a code, and a wrong "Copy code" button is worse than a missing one,
//! because the Mac can be set to copy it without asking.
//!
//! Nothing here logs. A code is a secret for a few minutes and does not belong in a log.

/// More than this is never a message with a code in it, and the scan stays cheap.
const MAX_CHARS: usize = 2000;
/// How far a word like "code" may be from the number it belongs to.
const NEAR_STRONG: usize = 50;
/// How far the name of a bank or an action word may be. These say less, so they must sit closer.
const NEAR_WEAK: usize = 30;
/// And they only vouch for a number that is long enough to be a code: "PayPal 2500 points" is not one.
const WEAK_MIN_DIGITS: usize = 6;
/// Names of banks only count in a short text, where the number can only be the point of it.
const WEAK_MAX_TEXT: usize = 240;
/// A year is a number that often follows a word like "code" by accident, so it needs the word right before it.
const NEAR_YEAR: usize = 14;

/// The one-time code in `text`, or `None`. Digits only for a plain code ("123-456" and "123 456"
/// come back as "123456"). The letters in front of a dash ("G-123456", "FB-12345") are a tag the
/// sender prints and the page that asks for the code already shows, so the digits come back. A code
/// that mixes letters and digits on both sides of the dash ("K7P-2XM") comes back as written.
pub fn find_code(text: &str) -> Option<String> {
    let chars: Vec<char> = text.chars().take(MAX_CHARS).collect();
    if chars.len() < 4 {
        return None;
    }
    // One lowercase char per char, so positions in both lists stay the same.
    let low: Vec<char> = chars.iter().map(|c| c.to_lowercase().next().unwrap_or(*c)).collect();

    let mut candidates = digit_candidates(&chars);
    candidates.extend(dashed_candidates(&chars));
    if candidates.is_empty() {
        return None;
    }
    let marks = keywords(&low);

    candidates
        .iter()
        .filter_map(|c| nearest(c, &marks, &chars).map(|rank| (rank, c)))
        .min_by_key(|(rank, c)| (*rank, c.start))
        .map(|(_, c)| c.value.clone())
}

struct Candidate {
    start: usize,
    end: usize,
    value: String,
    digits: usize,
    /// Looks like a year, which is the number most likely to follow a keyword by accident.
    year: bool,
}

// ---- Numbers ------------------------------------------------------------------------

fn is_dash(c: char) -> bool {
    // The hyphen, the non-breaking hyphen and the dashes that text messages use for it.
    matches!(c, '-' | '\u{2010}' | '\u{2011}' | '\u{2012}' | '\u{2013}')
}

fn is_gap(c: char) -> bool {
    matches!(c, ' ' | '\u{a0}' | '\u{202f}')
}

/// Runs of digits, joined when two of them are written as one code ("123 456", "1234-5678").
fn digit_candidates(chars: &[char]) -> Vec<Candidate> {
    let n = chars.len();
    let mut runs: Vec<(usize, usize)> = Vec::new();
    let mut i = 0;
    while i < n {
        if chars[i].is_ascii_digit() {
            let start = i;
            while i < n && chars[i].is_ascii_digit() {
                i += 1;
            }
            runs.push((start, i));
        } else {
            i += 1;
        }
    }

    let mut out = Vec::new();
    let mut k = 0;
    while k < runs.len() {
        // A chain is runs separated by exactly one space or dash.
        let mut chain = vec![runs[k]];
        let mut dashed = false;
        while k + 1 < runs.len() {
            let (_, end) = *chain.last().unwrap_or(&runs[k]);
            let next = runs[k + 1];
            let separator = chars[end];
            if next.0 == end + 1 && (is_gap(separator) || is_dash(separator)) {
                dashed |= is_dash(separator);
                chain.push(next);
                k += 1;
            } else {
                break;
            }
        }
        k += 1;

        let lens: Vec<usize> = chain.iter().map(|(s, e)| e - s).collect();
        if chain.len() == 2 && matches!((lens[0], lens[1]), (3, 3) | (4, 4)) {
            let (start, end) = (chain[0].0, chain[1].1);
            let value: String = chars[start..end].iter().filter(|c| c.is_ascii_digit()).collect();
            push_candidate(chars, start, end, value, &mut out);
        } else if !dashed {
            // Separated by spaces only: each number stands on its own ("1234 5 packages").
            for (start, end) in chain {
                if (4..=8).contains(&(end - start)) {
                    push_candidate(chars, start, end, chars[start..end].iter().collect(), &mut out);
                }
            }
        }
        // Dashes between other shapes are phone numbers, dates and reference numbers.
    }
    out
}

fn push_candidate(chars: &[char], start: usize, end: usize, value: String, out: &mut Vec<Candidate>) {
    if !edges_allow_code(chars, start, end) {
        return;
    }
    let digits = value.len();
    let year = digits == 4 && value.parse::<u32>().is_ok_and(|y| (1900..=2099).contains(&y));
    out.push(Candidate { start, end, value, digits, year });
}

/// What stands right before and after a number decides whether it can be a code at all: an amount
/// has a currency sign, a date has slashes, a postcode has letters after it, an order number a
/// word like "nr" in front.
fn edges_allow_code(chars: &[char], start: usize, end: usize) -> bool {
    let n = chars.len();
    let before = start.checked_sub(1).map(|i| chars[i]);
    let after = chars.get(end).copied();

    if before.is_some_and(|c| c.is_alphabetic() || c == '_' || "€$£#+@%~/\\".contains(c)) {
        return false;
    }
    if after.is_some_and(|c| c.is_alphabetic() || c == '_' || "%@/\\".contains(c)) {
        return false;
    }
    // "1,234", "12.345", "12:3456": part of a bigger number, not a code.
    let digit_at = |i: Option<usize>| i.and_then(|i| chars.get(i)).is_some_and(|c| c.is_ascii_digit());
    if before.is_some_and(|c| matches!(c, '.' | ',' | ':')) && digit_at(start.checked_sub(2)) {
        return false;
    }
    if after.is_some_and(|c| matches!(c, '.' | ',' | ':')) && digit_at(Some(end + 1)) {
        return false;
    }
    // "ref-1234" and "1234-abc" are identifiers.
    if before.is_some_and(is_dash) {
        return false;
    }
    if after.is_some_and(is_dash) && chars.get(end + 1).is_some_and(|c| c.is_alphanumeric()) {
        return false;
    }
    // A Dutch postcode: four digits, two letters.
    let mut j = end;
    if j < n && is_gap(chars[j]) {
        j += 1;
    }
    if end - start == 4
        && j + 1 < n
        && chars[j].is_ascii_uppercase()
        && chars[j + 1].is_ascii_uppercase()
        && !chars.get(j + 2).is_some_and(|c| c.is_alphabetic())
    {
        return false;
    }
    // "10 euro", "1500 km", "2000 minutes".
    if UNITS.contains(&word_after(chars, end).as_str()) {
        return false;
    }
    // "order 123456", "bestelling 4821".
    !NUMBER_WORDS.contains(&word_before(chars, start).as_str())
}

/// Words that follow a number that is a measure, not a code.
const UNITS: &[&str] = &[
    "euro", "eur", "dollar", "usd", "cent", "km", "kg", "gram", "uur", "uren", "hour", "hours", "min", "mins", "minuten", "minute",
    "minutes", "sec", "seconden", "seconds", "dagen", "days", "stuks", "pieces", "punten", "points",
];

/// Words that come before a number that is a reference, not a code.
const NUMBER_WORDS: &[&str] = &[
    "nr", "no", "nummer", "number", "order", "ordernummer", "bestelling", "bestelnummer", "factuur", "factuurnummer", "invoice",
    "ref", "referentie", "reference", "kenmerk", "tracking", "klantnummer", "rekeningnummer", "iban", "id", "kaartnummer",
];

fn word_after(chars: &[char], end: usize) -> String {
    chars[end..]
        .iter()
        .skip_while(|c| is_gap(**c))
        .take_while(|c| c.is_alphabetic())
        .flat_map(|c| c.to_lowercase())
        .collect()
}

fn word_before(chars: &[char], start: usize) -> String {
    let mut j = start;
    // Past the gap, and one ":" or "." as in "nr.: 4821".
    while j > 0 && is_gap(chars[j - 1]) {
        j -= 1;
    }
    if j > 0 && matches!(chars[j - 1], ':' | '.') {
        j -= 1;
        while j > 0 && is_gap(chars[j - 1]) {
            j -= 1;
        }
    }
    let end = j;
    while j > 0 && chars[j - 1].is_alphabetic() {
        j -= 1;
    }
    chars[j..end].iter().flat_map(|c| c.to_lowercase()).collect()
}

/// Codes with letters and a dash: "G-123456", "FB-12345", "K7P-2XM". The dash and the letters are
/// part of the code as it was printed.
fn dashed_candidates(chars: &[char]) -> Vec<Candidate> {
    let n = chars.len();
    let mut out = Vec::new();
    let mut i = 0;
    while i < n {
        if !chars[i].is_ascii_alphanumeric() {
            i += 1;
            continue;
        }
        let start = i;
        while i < n && (chars[i].is_ascii_alphanumeric() || chars[i] == '-') {
            i += 1;
        }
        let mut end = i;
        while end > start && chars[end - 1] == '-' {
            end -= 1;
        }
        let token: String = chars[start..end].iter().collect();
        let Some((left, right)) = token.split_once('-') else { continue };
        if right.contains('-') || left.is_empty() || right.is_empty() {
            continue;
        }
        let upper_or_digit = |s: &str| s.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit());
        let has_digit = |s: &str| s.chars().any(|c| c.is_ascii_digit());
        let letters_then_digits = left.len() <= 4
            && left.chars().all(|c| c.is_ascii_uppercase())
            && (4..=8).contains(&right.len())
            && right.chars().all(|c| c.is_ascii_digit());
        let mixed = (3..=5).contains(&left.len())
            && (3..=5).contains(&right.len())
            && upper_or_digit(left)
            && upper_or_digit(right)
            && has_digit(left)
            && has_digit(right)
            && token.chars().any(|c| c.is_ascii_uppercase());
        if !(letters_then_digits || mixed) {
            continue;
        }
        // Not inside a web address, an e-mail address or a decimal.
        let before = start.checked_sub(1).map(|j| chars[j]);
        let after = chars.get(end).copied();
        if before.is_some_and(|c| "/@#$€£+%.".contains(c)) || after.is_some_and(|c| "/@%".contains(c)) {
            continue;
        }
        if after == Some('.') && chars.get(end + 1).is_some_and(|c| c.is_alphanumeric()) {
            continue;
        }
        let digits = token.chars().filter(|c| c.is_ascii_digit()).count();
        let value = if letters_then_digits { right.to_string() } else { token };
        out.push(Candidate { start, end, value, digits, year: false });
    }
    out
}

// ---- Words that say "this is a code" ----------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
enum Class {
    /// "code", "verification", "OTP": the text is about a code.
    Strong,
    /// The name of a bank or a service: it says where a code might come from.
    Brand,
    /// "confirm", "bevestig": a code is one of the things that can be confirmed, but so is a payment.
    Action,
}

struct Mark {
    start: usize,
    end: usize,
    class: Class,
}

#[derive(Clone, Copy)]
enum Shape {
    /// A whole word.
    Word,
    /// The start of a word: "verif" in "verification" and "verificatie".
    Prefix,
    /// The end of a word, which is how Dutch makes "inlogcode" and "verificatiecode".
    Suffix,
}

const STRONG: &[(&str, Shape)] = &[
    ("code", Shape::Suffix),
    ("codes", Shape::Suffix),
    ("kode", Shape::Suffix),
    ("otp", Shape::Word),
    ("pin", Shape::Word),
    ("tan", Shape::Word),
    ("2fa", Shape::Word),
    ("mfa", Shape::Word),
    ("password", Shape::Word),
    ("wachtwoord", Shape::Word),
    ("passcode", Shape::Word),
    ("verif", Shape::Prefix),
    ("authenti", Shape::Prefix),
    ("inlog", Shape::Prefix),
    ("login", Shape::Word),
    ("log in", Shape::Word),
    ("log-in", Shape::Word),
    ("sign in", Shape::Word),
    ("sign-in", Shape::Word),
    ("signin", Shape::Word),
    ("one time", Shape::Word),
    ("one-time", Shape::Word),
    ("eenmalig", Shape::Prefix),
    ("security", Shape::Word),
    ("beveiliging", Shape::Prefix),
    ("beveiligings", Shape::Prefix),
];

const ACTIONS: &[(&str, Shape)] = &[
    ("confirm", Shape::Prefix),
    ("bevestig", Shape::Prefix),
    ("authorize", Shape::Prefix),
    ("authorise", Shape::Prefix),
    ("autoriseer", Shape::Prefix),
];

const BRANDS: &[&str] = &[
    "ing", "rabobank", "rabo", "abn amro", "abn", "amro", "sns", "asn", "bunq", "knab", "triodos", "regiobank", "lanschot", "n26",
    "revolut", "bancontact", "belfius", "kbc", "bnp", "paypal", "digid", "tikkie", "mastercard", "visa", "adyen", "google",
    "microsoft", "apple", "amazon", "facebook", "instagram", "whatsapp", "telegram", "discord", "uber", "linkedin", "tiktok",
    "snapchat", "steam",
];

/// Things that are "code" in a word of their own, but not the kind that is sent as a secret.
const CODE_PREFIXES: &[&str] = &[
    "post", "zip", "bar", "qr", "promo", "korting", "kortings", "coupon", "voucher", "actie", "fout", "error", "gift", "cadeau",
    "tracking", "area", "land", "country", "source", "bron", "kleur", "color", "colour", "de", "en", "uni", "hex", "dial",
];

/// The same for "code" as a word of its own, with the word before it: "postal code", "promo code".
const CODE_BEFORE: &[&str] = &[
    "postal", "post", "zip", "area", "country", "promo", "discount", "coupon", "voucher", "gift", "referral", "invite", "error",
    "tracking", "qr", "bar", "source", "korting", "kortings", "actie", "uitnodiging", "fout", "land", "kleur", "color", "colour",
    "dial", "streepjes",
];

fn keywords(low: &[char]) -> Vec<Mark> {
    let mut marks = Vec::new();
    for (word, shape) in STRONG {
        find_all(low, word, *shape, Class::Strong, &mut marks);
    }
    for (word, shape) in ACTIONS {
        find_all(low, word, *shape, Class::Action, &mut marks);
    }
    for word in BRANDS {
        find_all(low, word, Shape::Word, Class::Brand, &mut marks);
    }
    marks
}

fn find_all(low: &[char], word: &str, shape: Shape, class: Class, out: &mut Vec<Mark>) {
    let len = word.chars().count();
    if low.len() < len {
        return;
    }
    for start in 0..=(low.len() - len) {
        if !word.chars().enumerate().all(|(k, c)| low[start + k] == c) {
            continue;
        }
        let end = start + len;
        let starts_word = start == 0 || !low[start - 1].is_alphanumeric();
        let ends_word = end == low.len() || !low[end].is_alphanumeric();
        let fits = match shape {
            Shape::Word => starts_word && ends_word,
            Shape::Prefix => starts_word,
            Shape::Suffix => ends_word,
        };
        if !fits {
            continue;
        }
        if matches!(shape, Shape::Suffix) && is_other_kind_of_code(low, start) {
            continue;
        }
        out.push(Mark { start, end, class });
    }
}

/// "postcode", "barcode", "promo code": a code, but not one to copy.
fn is_other_kind_of_code(low: &[char], start: usize) -> bool {
    let mut word_start = start;
    while word_start > 0 && low[word_start - 1].is_alphanumeric() {
        word_start -= 1;
    }
    if word_start < start {
        let prefix: String = low[word_start..start].iter().collect();
        return CODE_PREFIXES.contains(&prefix.as_str());
    }
    // A word of its own: look at the one before it, past a space or a dash ("qr-code").
    let mut j = start;
    while j > 0 && (is_gap(low[j - 1]) || is_dash(low[j - 1])) {
        j -= 1;
    }
    let mut k = j;
    while k > 0 && low[k - 1].is_alphabetic() {
        k -= 1;
    }
    let before: String = low[k..j].iter().collect();
    CODE_BEFORE.contains(&before.as_str())
}

/// How well a number is vouched for: a word that says "code" beats a bank's name whatever the
/// distance, and then the closer word wins. `None` when no word is close enough.
fn nearest(candidate: &Candidate, marks: &[Mark], chars: &[char]) -> Option<(u8, usize)> {
    let mut best: Option<(u8, usize)> = None;
    for mark in marks {
        let (from, to) = if mark.end <= candidate.start {
            (mark.end, candidate.start)
        } else if candidate.end <= mark.start {
            (candidate.end, mark.start)
        } else {
            continue;
        };
        let distance = to - from;
        let allowed = match mark.class {
            Class::Strong => distance <= NEAR_STRONG,
            Class::Brand | Class::Action => {
                distance <= NEAR_WEAK && chars.len() <= WEAK_MAX_TEXT && candidate.digits >= WEAK_MIN_DIGITS
            }
        };
        if !allowed || crosses_sentence(&chars[from..to]) {
            continue;
        }
        if candidate.year && !(mark.class == Class::Strong && mark.end <= candidate.start && distance <= NEAR_YEAR) {
            continue;
        }
        let rank = (if mark.class == Class::Strong { 0 } else { 1 }, distance);
        best = Some(best.map_or(rank, |b| b.min(rank)));
    }
    best
}

/// A full stop, question mark, exclamation mark or line break between the two means they are not
/// about each other: "Code expired. 1234 euro" is not a code.
fn crosses_sentence(between: &[char]) -> bool {
    between.iter().enumerate().any(|(i, c)| match c {
        '\n' | '\r' => true,
        '.' | '!' | '?' => between.get(i + 1).is_none_or(|next| is_gap(*next)),
        _ => false,
    })
}

#[cfg(test)]
mod tests {
    use super::find_code;

    fn code(text: &str) -> Option<String> {
        find_code(text)
    }

    fn expect(text: &str, want: &str) {
        assert_eq!(code(text).as_deref(), Some(want), "text: {text}");
    }

    fn none(text: &str) {
        assert_eq!(code(text), None, "text: {text}");
    }

    // ---- English --------------------------------------------------------------------

    #[test]
    fn english_verification_code() {
        expect("Your verification code is 482913", "482913");
        expect("Your verification code is 482913.", "482913");
        expect("Use 482913 as your verification code", "482913");
    }

    #[test]
    fn english_code_first() {
        expect("482913 is your Amazon OTP. Do not share it.", "482913");
        expect("123456 is your Google verification code.", "123456");
        expect("4821 is your Uber code", "4821");
    }

    #[test]
    fn english_with_colon() {
        expect("Your Apple ID Code is: 123456. Don't share it with anyone.", "123456");
        expect("Login code: 83921", "83921");
        expect("Code:123456", "123456");
        expect("Security code: 1234567", "1234567");
        expect("PIN: 7402", "7402");
        expect("Your one-time password is 55123", "55123");
    }

    #[test]
    fn eight_digit_codes() {
        expect("Your Microsoft account security code is 12345678", "12345678");
        expect("Enter 87654321 to sign in", "87654321");
    }

    #[test]
    fn a_tag_before_the_dash_is_left_off() {
        expect("G-123456 is your Google verification code.", "123456");
        expect("FB-12345 is your Facebook confirmation code", "12345");
    }

    #[test]
    fn dashed_alphanumeric_codes_stay_as_written() {
        expect("Your verification code is K7P-2XM", "K7P-2XM");
        expect("Use code AB12-CD34 to sign in", "AB12-CD34");
    }

    #[test]
    fn split_digits_are_joined() {
        expect("Use 123-456 to log in", "123456");
        expect("Verification code: 123 456", "123456");
        expect("Your security code is 1234 5678", "12345678");
        expect("Your code is 123\u{2013}456", "123456");
        expect("Your code is 123\u{a0}456", "123456");
    }

    #[test]
    fn the_code_wins_over_other_numbers() {
        expect("Order 123456 shipped. Your code is 9876", "9876");
        expect("Your code 4821 is valid for 10 minutes", "4821");
        expect("Your code is 482913. Use it within 10 minutes, before 12:30.", "482913");
    }

    #[test]
    fn a_bank_name_is_enough_in_a_short_text() {
        expect("ABN AMRO: use 482913 to approve this payment", "482913");
        expect("Revolut: 482913 to approve", "482913");
    }

    #[test]
    fn confirming_with_a_long_enough_number() {
        expect("Enter 12345678 to confirm your payment", "12345678");
    }

    #[test]
    fn the_nearest_number_to_the_word_wins() {
        expect("PayPal 2500 transfers pending. Your PayPal security code: 665544", "665544");
    }

    #[test]
    fn english_things_that_are_not_codes() {
        none("You have 3 new messages");
        none("Order 123456789012 shipped");
        none("Your package 483920 will arrive tomorrow");
        none("PayPal: you have 2500 points");
        none("Your balance is $1,234.56 after the purchase. Confirm in the app.");
        none("Order #123456 confirmation");
        none("Order number 483920 is on its way");
        none("Reference 123456");
        none("Zip code 12345");
        none("Postal code 90210, Beverly Hills");
        none("Use promo code 2024 for a discount");
        none("Barcode 12345678 scanned");
        none("Meeting on 2024-05-12 at 14:30");
        none("Call me at 06-12345678 for the code");
        none("Your code expired. 4821 people agree");
        none("Download 2000 minutes of music");
        none("Your verification code");
        none("");
        none("   ");
        none("1234");
    }

    #[test]
    fn a_year_only_counts_right_behind_the_word() {
        expect("Code: 2024", "2024");
        expect("Your PIN is 1987", "1987");
        none("Your code for the big summer sale of 2024 is ready in the app");
    }

    // ---- Dutch ----------------------------------------------------------------------

    #[test]
    fn dutch_code() {
        expect("Je code: 7391. Deel deze met niemand.", "7391");
        expect("Uw verificatiecode voor Rabobank is 482913", "482913");
        expect("Jouw inlogcode is 123456. Deel deze code met niemand.", "123456");
        expect("123456 is jouw Bol.com code", "123456");
    }

    #[test]
    fn dutch_compound_words() {
        expect("ING: Je bevestigingscode is 8291. Deze code is 5 minuten geldig.", "8291");
        expect("DigiD: je SMS-code is 593214", "593214");
        expect("Uw beveiligingscode is 48213", "48213");
        expect("Je pincode is 4455", "4455");
        expect("Toegangscode: 902817", "902817");
        expect("Je activatiecode is 6612", "6612");
    }

    #[test]
    fn dutch_other_words() {
        expect("Uw eenmalige wachtwoord is 55123", "55123");
        expect("Je wachtwoord is 482913", "482913");
        expect("Verificatie: 73920", "73920");
        expect("Inloggen met 482913", "482913");
        expect("Gebruik 5821 3340 om je betaling te bevestigen", "58213340");
    }

    #[test]
    fn dutch_things_that_are_not_codes() {
        none("Je pakket 4821 wordt vandaag bezorgd");
        none("Betaling van \u{20ac} 1234 ontvangen");
        none("Betaling van \u{20ac}1234,50 ontvangen");
        none("Postcode 1234 AB, Amsterdam");
        none("Uw postcode is 1011 AB");
        none("Je hebt 2024 kilometer gereden");
        none("Je hebt 5000 euro ontvangen");
        none("Bestelling #123456 is bevestigd");
        none("Bestelling 123456 is bevestigd");
        none("Tikkie: Mark vraagt \u{20ac} 12,50 voor de pizza");
        none("Bel 020 1234567 voor vragen");
        none("Uw kortingscode ZOMER2024 is geldig tot 31-08-2024");
        none("Gebruik kortingscode 4821 bij het afrekenen");
        none("Je verificatiecode is verlopen");
    }

    #[test]
    fn messages_as_services_really_send_them() {
        expect("<#> Your WhatsApp code: 123-456 You can also tap on this link to verify your phone: v.whatsapp.com/123456", "123456");
        expect("Telegram code: 12345. Do not give this code to anyone, even if they say they are from Telegram!", "12345");
        expect("Your Booking.com verification code is 12345678.", "12345678");
        expect("Use 6-digit code 123456 to continue", "123456");
        expect("Rabobank: Uw Rabo Scanner antwoord is 12345678", "12345678");
        expect("Dit is je ING code: 123456. Gebruik deze code niet voor andere dingen.", "123456");
        expect("Uw eenmalige activatiecode voor Mijn Overheid is 123456", "123456");
    }

    // ---- Shape of the input ---------------------------------------------------------

    #[test]
    fn works_in_a_title_and_text_joined_the_way_the_phone_does() {
        expect("Rabobank Je inlogcode is 123456", "123456");
        expect("Messages 482913 is your verification code", "482913");
    }

    #[test]
    fn upper_and_lower_case_do_not_matter() {
        expect("VERIFICATION CODE: 482913", "482913");
        expect("verificatiecode 482913", "482913");
        expect("Otp 5566", "5566");
    }

    #[test]
    fn a_line_break_ends_the_sentence() {
        expect("Your code is 482913\nDo not share it", "482913");
        none("Your code\n482913 people");
    }

    #[test]
    fn letters_glued_to_the_number_make_it_something_else() {
        none("Your code is ABC1234");
        none("Your code is 1234abc");
        none("Code https://example.com/verify/123456");
        none("Code mail 123456@example.com");
    }

    #[test]
    fn a_code_is_between_four_and_eight_digits() {
        none("Your code is 123");
        none("Your code is 123456789");
        expect("Your code is 1234", "1234");
        expect("Your code is 12345678", "12345678");
    }

    #[test]
    fn long_text_is_cut_off_and_still_answers() {
        let mut text = "Your verification code is 482913. ".to_string();
        text.push_str(&"x".repeat(10_000));
        expect(&text, "482913");
        let mut late = "x".repeat(10_000);
        late.push_str(" Your verification code is 482913");
        none(&late);
    }

    #[test]
    fn non_ascii_text_keeps_positions_straight() {
        expect("\u{130}\u{130}\u{130} Uw inlogcode is 482913", "482913");
        expect("\u{1f512} Verification code: 482913", "482913");
    }
}

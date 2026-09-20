//! Bounded HTML/JS/CSS highlighting. Offsets are UTF-16 code units, as in Android text.
//! This lexer never changes source text and does not attempt compiler-level syntax validation.
use jni::objects::{JCharArray, JClass, JIntArray};
use jni::sys::jintArray;
use jni::JNIEnv;

const MAX_CHARS: usize = 1_000_000;
const MAX_BLOCKS: usize = 4_096;
const MAX_BLOCK_CHARS: usize = 768;
const MAX_SPANS: usize = 384;
// SourceToken ordinals are the JNI contract.
const TAG: i32 = 0;
const ATTRIBUTE: i32 = 1;
const STRING: i32 = 2;
const COMMENT: i32 = 3;
const KEYWORD: i32 = 4;
const NUMBER: i32 = 5;
const PUNCTUATION: i32 = 6;

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_ui_devtools_SourceHighlighter_nativeSpans(
    env: JNIEnv,
    _class: JClass,
    input: JCharArray,
    block_ends: JIntArray,
) -> jintArray {
    let result = (|| {
        let length = env.get_array_length(&input)? as usize;
        let count = env.get_array_length(&block_ends)? as usize;
        if length > MAX_CHARS || count > MAX_BLOCKS {
            return Ok(None);
        }
        let mut units = vec![0; length];
        let mut ends = vec![0; count];
        env.get_char_array_region(&input, 0, &mut units)?;
        env.get_int_array_region(&block_ends, 0, &mut ends)?;
        let Some(spans) = highlight(&units, &ends) else {
            return Ok(None);
        };
        let result = env.new_int_array(spans.len() as i32)?;
        env.set_int_array_region(&result, 0, &spans)?;
        Ok::<_, jni::errors::Error>(Some(result.into_raw()))
    })();
    match result {
        Ok(Some(value)) => value,
        Ok(None) => std::ptr::null_mut(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn starts(input: &[u16], at: usize, text: &[u8], ignore_case: bool) -> bool {
    input.get(at..at + text.len()).is_some_and(|slice| {
        slice.iter().zip(text).all(|(&unit, &byte)| {
            unit == u16::from(byte)
                || (ignore_case && unit <= 127 && (unit as u8).eq_ignore_ascii_case(&byte))
        })
    })
}

fn find(input: &[u16], at: usize, text: &[u8], ignore_case: bool) -> Option<usize> {
    (at..input.len()).find(|&i| starts(input, i, text, ignore_case))
}

fn closing(input: &[u16], at: usize, text: &[u8]) -> usize {
    find(input, at, text, false).map_or(input.len(), |i| i + text.len())
}

fn quoted(input: &[u16], at: usize, escapes: bool) -> usize {
    let quote = input[at];
    let mut next = at + 1;
    while next < input.len() {
        let unit = input[next];
        next += 1;
        if escapes && unit == u16::from(b'\\') && next < input.len() {
            next += 1;
        } else if unit == quote {
            break;
        }
    }
    next
}

// Character classification reproduces the legacy Kotlin lexer exactly. Kotlin's
// Char.isDigit()/isLetter()/isWhitespace() delegate to java.lang.Character on UTF-16
// units, and Rust's char::is_numeric/is_alphabetic/is_whitespace disagree with those on
// real inputs: ½ and ² (category No) and Ⅳ (Nl) would join NUMBER tokens, combining
// marks (Mn, e.g. U+05B0) would break KEYWORD recognition, U+0085 would count as
// whitespace while U+001C..U+001F would not. The tables below are therefore generated
// from OpenJDK 26.0.2 (Character.isDigit, Character.isLetter, and
// Character.isWhitespace || Character.isSpaceChar) over the whole BMP; regenerating
// with another JDK can be diffed against them.
// Reproduce with: python3 rust/tools/generate-java-char-tables.py --check
// This pins Unicode data for stable highlighting; it is not the runtime Android table.
/// Character.isDigit on OpenJDK 26.0.2 (decimal digits, general category Nd).
static JAVA_DIGIT_RANGES: [(u16, u16); 37] = [
    (0x0030, 0x0039),
    (0x0660, 0x0669),
    (0x06F0, 0x06F9),
    (0x07C0, 0x07C9),
    (0x0966, 0x096F),
    (0x09E6, 0x09EF),
    (0x0A66, 0x0A6F),
    (0x0AE6, 0x0AEF),
    (0x0B66, 0x0B6F),
    (0x0BE6, 0x0BEF),
    (0x0C66, 0x0C6F),
    (0x0CE6, 0x0CEF),
    (0x0D66, 0x0D6F),
    (0x0DE6, 0x0DEF),
    (0x0E50, 0x0E59),
    (0x0ED0, 0x0ED9),
    (0x0F20, 0x0F29),
    (0x1040, 0x1049),
    (0x1090, 0x1099),
    (0x17E0, 0x17E9),
    (0x1810, 0x1819),
    (0x1946, 0x194F),
    (0x19D0, 0x19D9),
    (0x1A80, 0x1A89),
    (0x1A90, 0x1A99),
    (0x1B50, 0x1B59),
    (0x1BB0, 0x1BB9),
    (0x1C40, 0x1C49),
    (0x1C50, 0x1C59),
    (0xA620, 0xA629),
    (0xA8D0, 0xA8D9),
    (0xA900, 0xA909),
    (0xA9D0, 0xA9D9),
    (0xA9F0, 0xA9F9),
    (0xAA50, 0xAA59),
    (0xABF0, 0xABF9),
    (0xFF10, 0xFF19),
];

/// Character.isLetter on OpenJDK 26.0.2 (Lu/Ll/Lt/Lm/Lo; unlike char::is_alphabetic this excludes combining marks and letter numbers such as U+2160).
static JAVA_LETTER_RANGES: [(u16, u16); 377] = [
    (0x0041, 0x005A),
    (0x0061, 0x007A),
    (0x00AA, 0x00AA),
    (0x00B5, 0x00B5),
    (0x00BA, 0x00BA),
    (0x00C0, 0x00D6),
    (0x00D8, 0x00F6),
    (0x00F8, 0x02C1),
    (0x02C6, 0x02D1),
    (0x02E0, 0x02E4),
    (0x02EC, 0x02EC),
    (0x02EE, 0x02EE),
    (0x0370, 0x0374),
    (0x0376, 0x0377),
    (0x037A, 0x037D),
    (0x037F, 0x037F),
    (0x0386, 0x0386),
    (0x0388, 0x038A),
    (0x038C, 0x038C),
    (0x038E, 0x03A1),
    (0x03A3, 0x03F5),
    (0x03F7, 0x0481),
    (0x048A, 0x052F),
    (0x0531, 0x0556),
    (0x0559, 0x0559),
    (0x0560, 0x0588),
    (0x05D0, 0x05EA),
    (0x05EF, 0x05F2),
    (0x0620, 0x064A),
    (0x066E, 0x066F),
    (0x0671, 0x06D3),
    (0x06D5, 0x06D5),
    (0x06E5, 0x06E6),
    (0x06EE, 0x06EF),
    (0x06FA, 0x06FC),
    (0x06FF, 0x06FF),
    (0x0710, 0x0710),
    (0x0712, 0x072F),
    (0x074D, 0x07A5),
    (0x07B1, 0x07B1),
    (0x07CA, 0x07EA),
    (0x07F4, 0x07F5),
    (0x07FA, 0x07FA),
    (0x0800, 0x0815),
    (0x081A, 0x081A),
    (0x0824, 0x0824),
    (0x0828, 0x0828),
    (0x0840, 0x0858),
    (0x0860, 0x086A),
    (0x0870, 0x0887),
    (0x0889, 0x088F),
    (0x08A0, 0x08C9),
    (0x0904, 0x0939),
    (0x093D, 0x093D),
    (0x0950, 0x0950),
    (0x0958, 0x0961),
    (0x0971, 0x0980),
    (0x0985, 0x098C),
    (0x098F, 0x0990),
    (0x0993, 0x09A8),
    (0x09AA, 0x09B0),
    (0x09B2, 0x09B2),
    (0x09B6, 0x09B9),
    (0x09BD, 0x09BD),
    (0x09CE, 0x09CE),
    (0x09DC, 0x09DD),
    (0x09DF, 0x09E1),
    (0x09F0, 0x09F1),
    (0x09FC, 0x09FC),
    (0x0A05, 0x0A0A),
    (0x0A0F, 0x0A10),
    (0x0A13, 0x0A28),
    (0x0A2A, 0x0A30),
    (0x0A32, 0x0A33),
    (0x0A35, 0x0A36),
    (0x0A38, 0x0A39),
    (0x0A59, 0x0A5C),
    (0x0A5E, 0x0A5E),
    (0x0A72, 0x0A74),
    (0x0A85, 0x0A8D),
    (0x0A8F, 0x0A91),
    (0x0A93, 0x0AA8),
    (0x0AAA, 0x0AB0),
    (0x0AB2, 0x0AB3),
    (0x0AB5, 0x0AB9),
    (0x0ABD, 0x0ABD),
    (0x0AD0, 0x0AD0),
    (0x0AE0, 0x0AE1),
    (0x0AF9, 0x0AF9),
    (0x0B05, 0x0B0C),
    (0x0B0F, 0x0B10),
    (0x0B13, 0x0B28),
    (0x0B2A, 0x0B30),
    (0x0B32, 0x0B33),
    (0x0B35, 0x0B39),
    (0x0B3D, 0x0B3D),
    (0x0B5C, 0x0B5D),
    (0x0B5F, 0x0B61),
    (0x0B71, 0x0B71),
    (0x0B83, 0x0B83),
    (0x0B85, 0x0B8A),
    (0x0B8E, 0x0B90),
    (0x0B92, 0x0B95),
    (0x0B99, 0x0B9A),
    (0x0B9C, 0x0B9C),
    (0x0B9E, 0x0B9F),
    (0x0BA3, 0x0BA4),
    (0x0BA8, 0x0BAA),
    (0x0BAE, 0x0BB9),
    (0x0BD0, 0x0BD0),
    (0x0C05, 0x0C0C),
    (0x0C0E, 0x0C10),
    (0x0C12, 0x0C28),
    (0x0C2A, 0x0C39),
    (0x0C3D, 0x0C3D),
    (0x0C58, 0x0C5A),
    (0x0C5C, 0x0C5D),
    (0x0C60, 0x0C61),
    (0x0C80, 0x0C80),
    (0x0C85, 0x0C8C),
    (0x0C8E, 0x0C90),
    (0x0C92, 0x0CA8),
    (0x0CAA, 0x0CB3),
    (0x0CB5, 0x0CB9),
    (0x0CBD, 0x0CBD),
    (0x0CDC, 0x0CDE),
    (0x0CE0, 0x0CE1),
    (0x0CF1, 0x0CF2),
    (0x0D04, 0x0D0C),
    (0x0D0E, 0x0D10),
    (0x0D12, 0x0D3A),
    (0x0D3D, 0x0D3D),
    (0x0D4E, 0x0D4E),
    (0x0D54, 0x0D56),
    (0x0D5F, 0x0D61),
    (0x0D7A, 0x0D7F),
    (0x0D85, 0x0D96),
    (0x0D9A, 0x0DB1),
    (0x0DB3, 0x0DBB),
    (0x0DBD, 0x0DBD),
    (0x0DC0, 0x0DC6),
    (0x0E01, 0x0E30),
    (0x0E32, 0x0E33),
    (0x0E40, 0x0E46),
    (0x0E81, 0x0E82),
    (0x0E84, 0x0E84),
    (0x0E86, 0x0E8A),
    (0x0E8C, 0x0EA3),
    (0x0EA5, 0x0EA5),
    (0x0EA7, 0x0EB0),
    (0x0EB2, 0x0EB3),
    (0x0EBD, 0x0EBD),
    (0x0EC0, 0x0EC4),
    (0x0EC6, 0x0EC6),
    (0x0EDC, 0x0EDF),
    (0x0F00, 0x0F00),
    (0x0F40, 0x0F47),
    (0x0F49, 0x0F6C),
    (0x0F88, 0x0F8C),
    (0x1000, 0x102A),
    (0x103F, 0x103F),
    (0x1050, 0x1055),
    (0x105A, 0x105D),
    (0x1061, 0x1061),
    (0x1065, 0x1066),
    (0x106E, 0x1070),
    (0x1075, 0x1081),
    (0x108E, 0x108E),
    (0x10A0, 0x10C5),
    (0x10C7, 0x10C7),
    (0x10CD, 0x10CD),
    (0x10D0, 0x10FA),
    (0x10FC, 0x1248),
    (0x124A, 0x124D),
    (0x1250, 0x1256),
    (0x1258, 0x1258),
    (0x125A, 0x125D),
    (0x1260, 0x1288),
    (0x128A, 0x128D),
    (0x1290, 0x12B0),
    (0x12B2, 0x12B5),
    (0x12B8, 0x12BE),
    (0x12C0, 0x12C0),
    (0x12C2, 0x12C5),
    (0x12C8, 0x12D6),
    (0x12D8, 0x1310),
    (0x1312, 0x1315),
    (0x1318, 0x135A),
    (0x1380, 0x138F),
    (0x13A0, 0x13F5),
    (0x13F8, 0x13FD),
    (0x1401, 0x166C),
    (0x166F, 0x167F),
    (0x1681, 0x169A),
    (0x16A0, 0x16EA),
    (0x16F1, 0x16F8),
    (0x1700, 0x1711),
    (0x171F, 0x1731),
    (0x1740, 0x1751),
    (0x1760, 0x176C),
    (0x176E, 0x1770),
    (0x1780, 0x17B3),
    (0x17D7, 0x17D7),
    (0x17DC, 0x17DC),
    (0x1820, 0x1878),
    (0x1880, 0x1884),
    (0x1887, 0x18A8),
    (0x18AA, 0x18AA),
    (0x18B0, 0x18F5),
    (0x1900, 0x191E),
    (0x1950, 0x196D),
    (0x1970, 0x1974),
    (0x1980, 0x19AB),
    (0x19B0, 0x19C9),
    (0x1A00, 0x1A16),
    (0x1A20, 0x1A54),
    (0x1AA7, 0x1AA7),
    (0x1B05, 0x1B33),
    (0x1B45, 0x1B4C),
    (0x1B83, 0x1BA0),
    (0x1BAE, 0x1BAF),
    (0x1BBA, 0x1BE5),
    (0x1C00, 0x1C23),
    (0x1C4D, 0x1C4F),
    (0x1C5A, 0x1C7D),
    (0x1C80, 0x1C8A),
    (0x1C90, 0x1CBA),
    (0x1CBD, 0x1CBF),
    (0x1CE9, 0x1CEC),
    (0x1CEE, 0x1CF3),
    (0x1CF5, 0x1CF6),
    (0x1CFA, 0x1CFA),
    (0x1D00, 0x1DBF),
    (0x1E00, 0x1F15),
    (0x1F18, 0x1F1D),
    (0x1F20, 0x1F45),
    (0x1F48, 0x1F4D),
    (0x1F50, 0x1F57),
    (0x1F59, 0x1F59),
    (0x1F5B, 0x1F5B),
    (0x1F5D, 0x1F5D),
    (0x1F5F, 0x1F7D),
    (0x1F80, 0x1FB4),
    (0x1FB6, 0x1FBC),
    (0x1FBE, 0x1FBE),
    (0x1FC2, 0x1FC4),
    (0x1FC6, 0x1FCC),
    (0x1FD0, 0x1FD3),
    (0x1FD6, 0x1FDB),
    (0x1FE0, 0x1FEC),
    (0x1FF2, 0x1FF4),
    (0x1FF6, 0x1FFC),
    (0x2071, 0x2071),
    (0x207F, 0x207F),
    (0x2090, 0x209C),
    (0x2102, 0x2102),
    (0x2107, 0x2107),
    (0x210A, 0x2113),
    (0x2115, 0x2115),
    (0x2119, 0x211D),
    (0x2124, 0x2124),
    (0x2126, 0x2126),
    (0x2128, 0x2128),
    (0x212A, 0x212D),
    (0x212F, 0x2139),
    (0x213C, 0x213F),
    (0x2145, 0x2149),
    (0x214E, 0x214E),
    (0x2183, 0x2184),
    (0x2C00, 0x2CE4),
    (0x2CEB, 0x2CEE),
    (0x2CF2, 0x2CF3),
    (0x2D00, 0x2D25),
    (0x2D27, 0x2D27),
    (0x2D2D, 0x2D2D),
    (0x2D30, 0x2D67),
    (0x2D6F, 0x2D6F),
    (0x2D80, 0x2D96),
    (0x2DA0, 0x2DA6),
    (0x2DA8, 0x2DAE),
    (0x2DB0, 0x2DB6),
    (0x2DB8, 0x2DBE),
    (0x2DC0, 0x2DC6),
    (0x2DC8, 0x2DCE),
    (0x2DD0, 0x2DD6),
    (0x2DD8, 0x2DDE),
    (0x2E2F, 0x2E2F),
    (0x3005, 0x3006),
    (0x3031, 0x3035),
    (0x303B, 0x303C),
    (0x3041, 0x3096),
    (0x309D, 0x309F),
    (0x30A1, 0x30FA),
    (0x30FC, 0x30FF),
    (0x3105, 0x312F),
    (0x3131, 0x318E),
    (0x31A0, 0x31BF),
    (0x31F0, 0x31FF),
    (0x3400, 0x4DBF),
    (0x4E00, 0xA48C),
    (0xA4D0, 0xA4FD),
    (0xA500, 0xA60C),
    (0xA610, 0xA61F),
    (0xA62A, 0xA62B),
    (0xA640, 0xA66E),
    (0xA67F, 0xA69D),
    (0xA6A0, 0xA6E5),
    (0xA717, 0xA71F),
    (0xA722, 0xA788),
    (0xA78B, 0xA7DC),
    (0xA7F1, 0xA801),
    (0xA803, 0xA805),
    (0xA807, 0xA80A),
    (0xA80C, 0xA822),
    (0xA840, 0xA873),
    (0xA882, 0xA8B3),
    (0xA8F2, 0xA8F7),
    (0xA8FB, 0xA8FB),
    (0xA8FD, 0xA8FE),
    (0xA90A, 0xA925),
    (0xA930, 0xA946),
    (0xA960, 0xA97C),
    (0xA984, 0xA9B2),
    (0xA9CF, 0xA9CF),
    (0xA9E0, 0xA9E4),
    (0xA9E6, 0xA9EF),
    (0xA9FA, 0xA9FE),
    (0xAA00, 0xAA28),
    (0xAA40, 0xAA42),
    (0xAA44, 0xAA4B),
    (0xAA60, 0xAA76),
    (0xAA7A, 0xAA7A),
    (0xAA7E, 0xAAAF),
    (0xAAB1, 0xAAB1),
    (0xAAB5, 0xAAB6),
    (0xAAB9, 0xAABD),
    (0xAAC0, 0xAAC0),
    (0xAAC2, 0xAAC2),
    (0xAADB, 0xAADD),
    (0xAAE0, 0xAAEA),
    (0xAAF2, 0xAAF4),
    (0xAB01, 0xAB06),
    (0xAB09, 0xAB0E),
    (0xAB11, 0xAB16),
    (0xAB20, 0xAB26),
    (0xAB28, 0xAB2E),
    (0xAB30, 0xAB5A),
    (0xAB5C, 0xAB69),
    (0xAB70, 0xABE2),
    (0xAC00, 0xD7A3),
    (0xD7B0, 0xD7C6),
    (0xD7CB, 0xD7FB),
    (0xF900, 0xFA6D),
    (0xFA70, 0xFAD9),
    (0xFB00, 0xFB06),
    (0xFB13, 0xFB17),
    (0xFB1D, 0xFB1D),
    (0xFB1F, 0xFB28),
    (0xFB2A, 0xFB36),
    (0xFB38, 0xFB3C),
    (0xFB3E, 0xFB3E),
    (0xFB40, 0xFB41),
    (0xFB43, 0xFB44),
    (0xFB46, 0xFBB1),
    (0xFBD3, 0xFD3D),
    (0xFD50, 0xFD8F),
    (0xFD92, 0xFDC7),
    (0xFDF0, 0xFDFB),
    (0xFE70, 0xFE74),
    (0xFE76, 0xFEFC),
    (0xFF21, 0xFF3A),
    (0xFF41, 0xFF5A),
    (0xFF66, 0xFFBE),
    (0xFFC2, 0xFFC7),
    (0xFFCA, 0xFFCF),
    (0xFFD2, 0xFFD7),
    (0xFFDA, 0xFFDC),
];

/// Character.isWhitespace || Character.isSpaceChar on OpenJDK 26.0.2 (includes U+001C..U+001F and non-breaking spaces; excludes U+0085, unlike char::is_whitespace).
static JAVA_WHITESPACE_RANGES: [(u16, u16); 9] = [
    (0x0009, 0x000D),
    (0x001C, 0x0020),
    (0x00A0, 0x00A0),
    (0x1680, 0x1680),
    (0x2000, 0x200A),
    (0x2028, 0x2029),
    (0x202F, 0x202F),
    (0x205F, 0x205F),
    (0x3000, 0x3000),
];

fn in_ranges(unit: u16, ranges: &[(u16, u16)]) -> bool {
    ranges
        .binary_search_by(|&(lo, hi)| {
            if unit < lo {
                std::cmp::Ordering::Greater
            } else if unit > hi {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_ok()
}

fn letter(unit: u16) -> bool {
    in_ranges(unit, &JAVA_LETTER_RANGES)
}

fn digit(unit: u16) -> bool {
    in_ranges(unit, &JAVA_DIGIT_RANGES)
}

fn whitespace(unit: u16) -> bool {
    in_ranges(unit, &JAVA_WHITESPACE_RANGES)
}

fn among(unit: u16, bytes: &[u8]) -> bool {
    unit < 128 && bytes.contains(&(unit as u8))
}

fn keyword(word: &[u16]) -> bool {
    // ASCII keywords fit on the stack; no per-identifier String allocation.
    let mut bytes = [0_u8; 10];
    if word.len() > bytes.len() {
        return false;
    }
    for (slot, &unit) in bytes.iter_mut().zip(word) {
        if unit > 127 {
            return false;
        }
        *slot = unit as u8;
    }
    matches!(
        &bytes[..word.len()],
        b"async"
            | b"await"
            | b"break"
            | b"case"
            | b"catch"
            | b"class"
            | b"const"
            | b"continue"
            | b"debugger"
            | b"default"
            | b"delete"
            | b"do"
            | b"else"
            | b"export"
            | b"extends"
            | b"false"
            | b"finally"
            | b"for"
            | b"from"
            | b"function"
            | b"get"
            | b"if"
            | b"import"
            | b"in"
            | b"instanceof"
            | b"let"
            | b"new"
            | b"null"
            | b"of"
            | b"return"
            | b"set"
            | b"static"
            | b"super"
            | b"switch"
            | b"this"
            | b"throw"
            | b"true"
            | b"try"
            | b"typeof"
            | b"undefined"
            | b"var"
            | b"void"
            | b"while"
            | b"with"
            | b"yield"
    )
}

struct Painter<'a> {
    ends: &'a [i32],
    block: usize,
    count: usize,
    spans: Vec<i32>,
}

impl Painter<'_> {
    fn paint(&mut self, from: usize, until: usize, token: i32) {
        while self.block < self.ends.len() && self.ends[self.block] as usize <= from {
            self.block += 1;
            self.count = 0;
        }
        let mut cursor = from;
        while cursor < until && self.block < self.ends.len() {
            let end = self.ends[self.block] as usize;
            let stop = until.min(end);
            if self.count < MAX_SPANS {
                self.spans
                    .extend_from_slice(&[cursor as i32, stop as i32, token]);
                self.count += 1;
            }
            cursor = stop;
            if cursor >= end {
                self.block += 1;
                self.count = 0;
            }
        }
    }
}

fn code(input: &[u16], from: usize, until: usize, css: bool, paint: &mut Painter<'_>) {
    let input = &input[..until];
    let mut cursor = from;
    while cursor < until {
        let start = cursor;
        let unit = input[cursor];
        if starts(input, cursor, b"/*", false) {
            cursor = closing(input, cursor + 2, b"*/");
            paint.paint(start, cursor, COMMENT);
        } else if !css && starts(input, cursor, b"//", false) {
            cursor = find(input, cursor + 2, b"\n", false).unwrap_or(until);
            paint.paint(start, cursor, COMMENT);
        } else if among(unit, b"\"'") || (!css && unit == u16::from(b'`')) {
            cursor = quoted(input, cursor, true);
            paint.paint(start, cursor, STRING);
        } else if digit(unit) || (css && unit == u16::from(b'#')) {
            cursor += 1;
            while cursor < until
                && (letter(input[cursor]) || digit(input[cursor]) || among(input[cursor], b".%_"))
            {
                cursor += 1;
            }
            paint.paint(start, cursor, NUMBER);
        } else if letter(unit) || among(unit, b"_$") || (css && among(unit, b"-@")) {
            cursor += 1;
            while cursor < until
                && (letter(input[cursor])
                    || digit(input[cursor])
                    || among(input[cursor], b"_$")
                    || (css && input[cursor] == u16::from(b'-')))
            {
                cursor += 1;
            }
            if css || keyword(&input[start..cursor]) {
                let mut next = cursor;
                while next < until && whitespace(input[next]) {
                    next += 1;
                }
                paint.paint(
                    start,
                    cursor,
                    if css && input.get(next) == Some(&u16::from(b':')) {
                        ATTRIBUTE
                    } else {
                        KEYWORD
                    },
                );
            }
        } else {
            cursor += 1;
            if among(unit, b"{}[]();:.,=+*/!?&|<>%-") {
                paint.paint(start, cursor, PUNCTUATION);
            }
        }
    }
}

fn highlight(input: &[u16], ends: &[i32]) -> Option<Vec<i32>> {
    if input.len() > MAX_CHARS || ends.len() > MAX_BLOCKS {
        return None;
    }
    let mut previous = 0;
    for &end in ends {
        if end <= previous || end as usize > input.len() || end - previous > MAX_BLOCK_CHARS as i32
        {
            return None;
        }
        previous = end;
    }
    if previous as usize != input.len() {
        return None;
    }
    let mut paint = Painter {
        ends,
        block: 0,
        count: 0,
        spans: Vec::with_capacity(input.len().min(48 * 1024)),
    };
    let mut position = 0;
    while position < input.len() {
        let start = position;
        if starts(input, position, b"<!--", false) {
            position = closing(input, position + 4, b"-->");
            paint.paint(start, position, COMMENT);
        } else if starts(input, position, b"<!", false) || starts(input, position, b"<?", false) {
            position = closing(input, position + 2, b">");
            paint.paint(start, position, KEYWORD);
        } else if input[position] == u16::from(b'<')
            && input
                .get(position + 1)
                .is_some_and(|&c| letter(c) || c == u16::from(b'/'))
        {
            position += 1;
            let is_closing = input[position] == u16::from(b'/');
            if is_closing {
                position += 1;
            }
            let name_start = position;
            while position < input.len()
                && (letter(input[position])
                    || digit(input[position])
                    || among(input[position], b"_$-:"))
            {
                position += 1;
            }
            let name = &input[name_start..position];
            let script = name.len() == 6 && starts(name, 0, b"script", true);
            let style = name.len() == 5 && starts(name, 0, b"style", true);
            paint.paint(start, position, TAG);
            let mut self_closing = false;
            while position < input.len() && input[position] != u16::from(b'>') {
                let attribute_start = position;
                let unit = input[position];
                if whitespace(unit) {
                    position += 1;
                } else if among(unit, b"\"'") {
                    position = quoted(input, position, false);
                    paint.paint(attribute_start, position, STRING);
                } else if unit == u16::from(b'=') {
                    position += 1;
                    paint.paint(attribute_start, position, PUNCTUATION);
                } else if unit == u16::from(b'/') {
                    position += 1;
                    self_closing = true;
                    paint.paint(attribute_start, position, TAG);
                } else {
                    position += 1;
                    while position < input.len()
                        && !whitespace(input[position])
                        && !among(input[position], b"=/>\"'")
                    {
                        position += 1;
                    }
                    paint.paint(attribute_start, position, ATTRIBUTE);
                }
            }
            if position < input.len() {
                paint.paint(position, position + 1, TAG);
                position += 1;
            }
            if !is_closing && !self_closing && (script || style) {
                let close: &[u8] = if script { b"</script" } else { b"</style" };
                let raw_end = (position..input.len())
                    .find(|&i| {
                        starts(input, i, close, true)
                            && input
                                .get(i + close.len())
                                .is_none_or(|&c| whitespace(c) || among(c, b">/"))
                    })
                    .unwrap_or(input.len());
                code(input, position, raw_end, style, &mut paint);
                position = raw_end;
            }
        } else if input[position] == u16::from(b'&') {
            position += 1;
            while position < input.len().min(start + 32)
                && (letter(input[position])
                    || digit(input[position])
                    || input[position] == u16::from(b'#'))
            {
                position += 1;
            }
            if input.get(position) == Some(&u16::from(b';')) {
                position += 1;
                paint.paint(start, position, NUMBER);
            }
        } else {
            position += 1;
        }
    }
    Some(paint.spans)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spans(source: &str) -> Vec<(String, i32)> {
        let input: Vec<u16> = source.encode_utf16().collect();
        highlight(&input, &[input.len() as i32])
            .unwrap()
            .as_chunks::<3>()
            .0
            .iter()
            .map(|s| {
                (
                    String::from_utf16_lossy(&input[s[0] as usize..s[1] as usize]),
                    s[2],
                )
            })
            .collect()
    }

    #[test]
    fn mixed_html_js_css_and_unicode() {
        let tokens = spans("😀<!--广告--><DIV title='😀'><script>const 名字=23;/*note*/</script><style>.x{color:#fff}</style>&amp;</DIV>");
        for (text, token) in [
            ("<!--广告-->", COMMENT),
            ("<DIV", TAG),
            ("title", ATTRIBUTE),
            ("'😀'", STRING),
            ("const", KEYWORD),
            ("23", NUMBER),
            ("/*note*/", COMMENT),
            ("color", ATTRIBUTE),
            ("#fff", NUMBER),
            ("&amp;", NUMBER),
        ] {
            assert!(tokens.contains(&(text.into(), token)), "missing {text}");
        }
    }

    #[test]
    fn unicode_classification_matches_the_legacy_kotlin_lexer() {
        // No/Nl numbers (½, ², Ⅳ) never join a NUMBER token; Nd digits like ٣ still do.
        for (source, number) in [
            ("<script>1½</script>", "1"),
            ("<script>2²</script>", "2"),
            ("<script>1.5e3²</script>", "1.5e3"),
            ("<script>1Ⅳ</script>", "1"),
        ] {
            let numbers = spans(source)
                .into_iter()
                .filter(|(_, token)| *token == NUMBER)
                .collect::<Vec<_>>();
            assert_eq!(numbers, vec![(number.into(), NUMBER)], "{source}");
        }
        assert!(spans("<script>٣</script>").contains(&("٣".into(), NUMBER)));
        // CSS keeps `px` a separate KEYWORD after a No digit.
        let css_width = spans("<style>width:1²px{color:#fff}</style>");
        assert!(css_width.contains(&("1".into(), NUMBER)));
        assert!(css_width.contains(&("px".into(), KEYWORD)));
        // A combining mark (Mn) is not a letter: `const` keeps KEYWORD highlighting.
        let combining = spans("<script>const\u{05B0} x=1;</script>");
        assert!(combining.contains(&("const".into(), KEYWORD)));
        assert!(combining.contains(&("1".into(), NUMBER)));
        // U+0085 is not whitespace (KEYWORD), U+001C..U+001F are (ATTRIBUTE).
        let nel = spans("<style>width\u{0085}:1px</style>");
        assert!(nel.contains(&("width".into(), KEYWORD)));
        for unit in ['\u{001C}', '\u{001D}', '\u{001E}', '\u{001F}'] {
            let separators = spans(&format!("<style>width{unit}:1px</style>"));
            assert!(
                separators.contains(&("width".into(), ATTRIBUTE)),
                "U+{:04X}",
                unit as u32
            );
        }
        // Non-breaking spaces stay whitespace in both lexers.
        assert!(spans("<style>width\u{00A0}:1px</style>").contains(&("width".into(), ATTRIBUTE)));
        assert!(spans("<style>width\u{2007}:1px</style>").contains(&("width".into(), ATTRIBUTE)));
    }

    #[test]
    fn quoted_comments_and_unterminated_tokens_do_not_escape_the_document() {
        assert!(
            spans("<script>let x='/* not comment */';//comment\n`template\\`tail`</script>")
                .contains(&("'/* not comment */'".into(), STRING))
        );
        assert_eq!(
            spans("<!--unclosed"),
            vec![("<!--unclosed".into(), COMMENT)]
        );
        assert!(spans("<script>const x='unfinished").contains(&("'unfinished".into(), STRING)));
        assert!(
            spans("<script>const x=1;</scriptx>const y=2;</script>")
                .iter()
                .filter(|(s, t)| s == "const" && *t == KEYWORD)
                .count()
                == 2
        );
    }

    #[test]
    fn limits_and_arbitrary_utf16_are_bounded() {
        assert!(highlight(&[1, 2], &[0, 2]).is_none());
        assert!(highlight(&[1, 2], &[3]).is_none());
        assert!(highlight(&[1, 2], &[1]).is_none());
        let input: Vec<u16> = ("<script>".to_owned() + &";".repeat(1528))
            .encode_utf16()
            .collect();
        let result = highlight(&input, &[768, 1536]).unwrap();
        assert!(result.len() / 3 <= 2 * MAX_SPANS);
        let mut seed = 7_u32;
        for length in 0..768 {
            let data: Vec<u16> = (0..length)
                .map(|_| {
                    seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                    seed as u16
                })
                .collect();
            let ends = if length == 0 { vec![] } else { vec![length] };
            let output = highlight(&data, &ends).unwrap();
            for span in output.as_chunks::<3>().0 {
                assert!(span[0] >= 0 && span[0] < span[1] && span[1] <= length);
            }
        }
    }
}

import Foundation

/// The rule that lets this Mac stay awake with the lid closed without a password each time (`KeepAwake`), and the shell script that puts
/// it in place as root (R-B26). The script writes the rule itself, into a file that only root can touch: a file the user writes could be
/// swapped between `visudo` checking it and `install` copying it.
public enum SudoRule {
    public static func line(user: String) -> String? {
        guard !user.isEmpty, user.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "._-".contains($0)) }) else { return nil }
        return "\(user) ALL=(root) NOPASSWD: /usr/bin/pmset -a disablesleep 0, /usr/bin/pmset -a disablesleep 1"
    }

    /// For `do shell script` in AppleScript: it sits in a string literal there, so it holds no double quote and no backslash.
    public static func script(user: String, rule: String, disabled: Bool) -> String? {
        guard let line = line(user: user) else { return nil }
        return "T=$(/usr/bin/mktemp /private/tmp/tandem-pmset.XXXXXX) || exit 1; "
            + "/bin/echo '\(line)' > $T && /usr/sbin/visudo -cf $T && /usr/bin/install -m 0440 -o root -g wheel $T \(rule); "
            + "S=$?; /bin/rm -f $T; [ $S -eq 0 ] && /usr/bin/pmset -a disablesleep \(disabled ? 1 : 0)"
    }
}

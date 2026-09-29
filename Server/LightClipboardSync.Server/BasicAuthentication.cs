using System.Text;

namespace LightClipboardSync.Server;

internal static class BasicAuthentication
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public static bool TryGetUser(string authorization, out Guid user) =>
        TryGetUser(authorization, out user, out _);

    public static bool TryGetUser(string authorization, out Guid user, out string failureReason)
    {
        user = default;
        failureReason = "invalid";
        if (authorization.Length == 0)
        {
            failureReason = "missing";
            return false;
        }
        if (!authorization.StartsWith("Basic ", StringComparison.OrdinalIgnoreCase))
        {
            failureReason = "scheme";
            return false;
        }

        try
        {
            var credentials = StrictUtf8.GetString(Convert.FromBase64String(authorization[6..].Trim()));
            var separator = credentials.IndexOf(':');
            var valid = separator > 0
                && separator == credentials.Length - 1
                && Guid.TryParseExact(credentials[..separator], "D", out user);
            if (!valid)
                failureReason = "credentials";
            return valid;
        }
        catch (FormatException)
        {
            failureReason = "base64";
            return false;
        }
        catch (DecoderFallbackException)
        {
            failureReason = "encoding";
            return false;
        }
    }
}

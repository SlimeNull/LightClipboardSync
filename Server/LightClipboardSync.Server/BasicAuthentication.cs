using System.Text;

namespace LightClipboardSync.Server;

internal static class BasicAuthentication
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public static bool TryGetUser(string authorization, out Guid user)
    {
        user = default;
        if (!authorization.StartsWith("Basic ", StringComparison.OrdinalIgnoreCase))
            return false;

        try
        {
            var credentials = StrictUtf8.GetString(Convert.FromBase64String(authorization[6..].Trim()));
            var separator = credentials.IndexOf(':');
            return separator > 0
                && separator == credentials.Length - 1
                && Guid.TryParseExact(credentials[..separator], "D", out user);
        }
        catch (FormatException)
        {
            return false;
        }
        catch (DecoderFallbackException)
        {
            return false;
        }
    }
}

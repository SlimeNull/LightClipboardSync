using System.Threading.Channels;

namespace LightClipboardSync.Server;

public sealed record ClipboardEvent(long Id, string Type, string? Content);

public sealed record ClipboardEntry(long Id, string Type, string ContentType, byte[] Data,
    string? Content, Guid? SourceClient);

public sealed class ClipboardStore
{
    private const int HistoryLimit = 3;
    private readonly object _gate = new();
    private readonly Dictionary<Guid, UserState> _users = new();
    private long _lastId;

    public ClipboardEvent Push(Guid user, string type, string contentType, byte[] data,
        string? content, Guid? sourceClient)
    {
        lock (_gate)
        {
            var state = GetOrCreate(user);
            var entry = new ClipboardEntry(++_lastId, type, contentType, data, content, sourceClient);
            state.Entries.AddLast(entry);
            if (state.Entries.Count > HistoryLimit)
                state.Entries.RemoveFirst();

            var notification = new ClipboardEvent(entry.Id, entry.Type, entry.Content);
            foreach (var subscriber in state.Subscribers)
            {
                if (subscriber.ClientId != sourceClient || sourceClient is null)
                    subscriber.Channel.Writer.TryWrite(notification);
            }
            return notification;
        }
    }

    public ClipboardEntry? Find(Guid user, long id)
    {
        lock (_gate)
        {
            if (!_users.TryGetValue(user, out var state))
                return null;
            return state.Entries.FirstOrDefault(entry => entry.Id == id);
        }
    }

    public Subscription Subscribe(Guid user, Guid? clientId)
    {
        lock (_gate)
        {
            var state = GetOrCreate(user);
            var subscription = new Subscription(this, user, clientId);
            state.Subscribers.Add(subscription);
            return subscription;
        }
    }

    private UserState GetOrCreate(Guid user)
    {
        if (!_users.TryGetValue(user, out var state))
            _users.Add(user, state = new UserState());
        return state;
    }

    private void Unsubscribe(Guid user, Subscription subscription)
    {
        lock (_gate)
        {
            if (_users.TryGetValue(user, out var state))
                state.Subscribers.Remove(subscription);
            subscription.Channel.Writer.TryComplete();
        }
    }

    private sealed class UserState
    {
        public LinkedList<ClipboardEntry> Entries { get; } = new();
        public List<Subscription> Subscribers { get; } = new();
    }

    public sealed class Subscription : IDisposable
    {
        private readonly ClipboardStore _store;
        private readonly Guid _user;
        private bool _disposed;

        internal Subscription(ClipboardStore store, Guid user, Guid? clientId)
        {
            _store = store;
            _user = user;
            ClientId = clientId;
            Channel = System.Threading.Channels.Channel.CreateBounded<ClipboardEvent>(
                new BoundedChannelOptions(32) { FullMode = BoundedChannelFullMode.DropOldest });
        }

        internal Guid? ClientId { get; }
        internal Channel<ClipboardEvent> Channel { get; }
        public ChannelReader<ClipboardEvent> Reader => Channel.Reader;

        public void Dispose()
        {
            if (_disposed)
                return;
            _disposed = true;
            _store.Unsubscribe(_user, this);
        }
    }
}

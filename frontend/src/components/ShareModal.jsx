import { useId, useState } from 'react';
import { Link2, Loader2 } from 'lucide-react';
import { useCreateShareLinkMutation, useListShareLinksQuery, useRevokeShareLinkMutation } from '../services/apiSlice';
import { useToast } from '../hooks/useToast';
import { useDemoInfo } from '../hooks/useDemoInfo';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import Modal, { ModalActions } from './Modal';
import CopyLinkField from './CopyLinkField';
import { primaryButton, secondaryButton } from './modalStyles';

const EXPIRATION_OPTIONS = [
    { label: '15 minutes', minutes: 15 },
    { label: '1 hour', minutes: 60 },
    { label: '24 hours', minutes: 60 * 24 },
    { label: '7 days', minutes: 60 * 24 * 7 },
];

// The choices a server allowing links of at most maxMinutes accepts (all of them when unlimited).
const expirationOptions = (maxMinutes) => {
    if (!maxMinutes) return EXPIRATION_OPTIONS;
    const allowed = EXPIRATION_OPTIONS.filter((opt) => opt.minutes <= maxMinutes);
    return allowed.length > 0 ? allowed : [{ label: `${maxMinutes} minutes`, minutes: maxMinutes }];
};

// Keyed by path, so sharing another item starts from a fresh dialog with its own request to make a
// link: no link, error or spinner from the previous one carries over, and its late answers land in a
// dialog that is gone. The list of links is per item regardless (see ActiveLinks).
const ShareModal = ({ isOpen, onClose, item }) =>
    item ? <ShareDialog key={item.path} isOpen={isOpen} onClose={onClose} item={item} /> : null;

const ShareDialog = ({ isOpen, onClose, item }) => {
    const [expirationMinutes, setExpirationMinutes] = useState(EXPIRATION_OPTIONS[2].minutes);
    const [createShareLink, created] = useCreateShareLinkMutation();
    // The public demo only allows short links. Until its limits have loaded, all choices show.
    const options = expirationOptions(useDemoInfo()?.maxShareMinutes);
    // A choice the limit rules out (such as the default) falls back to the longest one allowed.
    const minutes = options.some((opt) => opt.minutes === expirationMinutes)
        ? expirationMinutes
        : options[options.length - 1].minutes;
    const shareUrl = created.data?.url ?? null;
    const error = created.error ? readableError(created.error, 'Failed to create share link.') : null;

    // The outcome shows from the mutation's own state; a failure isn't thrown on to anyone.
    const handleGenerate = () => {
        createShareLink({ body: { path: item.path, expirationMinutes: minutes } });
    };

    const kind = item.isDirectory ? 'folder' : 'file';

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            title={
                <>
                    Share <span className="font-semibold">{item.name}</span>
                </>
            }
            icon={<Link2 className="h-6 w-6 text-blue-600" aria-hidden="true" />}
            iconClassName="bg-blue-100"
        >
            {!shareUrl ? (
                <div className="mt-4 space-y-4">
                    <div>
                        <label htmlFor="expiration" className="block text-sm font-medium text-gray-700 mb-1">
                            Link expires in
                        </label>
                        <select
                            id="expiration"
                            value={minutes}
                            onChange={(e) => setExpirationMinutes(Number(e.target.value))}
                            className="block w-full rounded-md border-gray-300 shadow-xs focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
                        >
                            {options.map((opt) => (
                                <option key={opt.minutes} value={opt.minutes}>
                                    {opt.label}
                                </option>
                            ))}
                        </select>
                    </div>
                    <p className="text-sm text-gray-500">
                        Anyone with the link can see and download this {kind} until it expires or is revoked. No account is required.
                        {item.isDirectory && ' The link always serves the folder as it is at the time of download, including files added later.'}
                    </p>
                    {error && <p className="text-sm text-red-500">{error}</p>}
                </div>
            ) : (
                <div className="mt-4 space-y-3">
                    <CopyLinkField url={shareUrl} label="Share link" />
                    <p className="text-sm text-gray-500">
                        This link expires in {options.find((o) => o.minutes === minutes)?.label || `${minutes} minutes`}.
                        Copy it now: it can't be shown again, though it can be revoked below.
                    </p>
                </div>
            )}
            {/* A new link refreshes this list through its cache tag. */}
            <ActiveLinks path={item.path} />
            <ModalActions>
                {!shareUrl && (
                    <button type="button" disabled={created.isLoading} className={primaryButton.blue} onClick={handleGenerate}>
                        {created.isLoading ? <Loader2 className="animate-spin h-5 w-5" /> : 'Generate link'}
                    </button>
                )}
                <button type="button" className={secondaryButton} onClick={onClose}>
                    {shareUrl ? 'Done' : 'Cancel'}
                </button>
            </ModalActions>
        </Modal>
    );
};

// The item's links that still work, each with a Revoke button. Only a hash of a link's token is
// stored, so a link's URL cannot be shown again here.
const ActiveLinks = ({ path }) => {
    // Listed again each time the dialog opens, so links that expired, or went with a deleted item,
    // don't show. currentData is only ever this path's, never another item's while this one loads.
    const {
        currentData: links = [],
        error: loadError,
        isFetching,
    } = useListShareLinksQuery({ path }, { refetchOnMountOrArgChange: true });
    const [revokeShareLink, revoking] = useRevokeShareLinkMutation();
    const headingId = useId();
    const { addToast } = useToast();

    // A revoked link drops out when the list is asked again, which revoking does through its tag.
    const revoke = async (link) => {
        try {
            await revokeShareLink({ id: link.id }).unwrap();
            addToast('Link revoked', 'success');
        } catch {
            // Shown below, from the mutation's state.
        }
    };

    const failure = revoking.error ?? loadError;
    const error = failure
        ? readableError(failure, revoking.error ? 'Could not revoke the link.' : 'Could not load the active links.')
        : null;
    const revokingId = revoking.isLoading ? revoking.originalArgs.id : null;

    if (links.length === 0 && !error) return null;

    return (
        <div className="mt-4">
            {links.length > 0 && (
                <>
                    <h4 id={headingId} className="text-sm font-medium text-gray-700 mb-1">
                        Active links
                    </h4>
                    <ul
                        aria-labelledby={headingId}
                        className="divide-y divide-gray-200 border border-gray-200 rounded-md max-h-48 overflow-y-auto"
                    >
                        {links.map((link) => (
                            <li key={link.id} className="px-3 py-2 flex items-center gap-2 justify-between">
                                <div className="text-sm">
                                    <div className="text-gray-900">Expires {formatDate(link.expiresAt)}</div>
                                    <div className="text-xs text-gray-500">
                                        Created {formatDate(link.createdAt)} · {link.createdBy}
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    // Also while the list is being asked again: until it answers, a link
                                    // on it may already be gone.
                                    disabled={revokingId !== null || isFetching}
                                    onClick={() => revoke(link)}
                                    aria-label={`Revoke the link that expires ${formatDate(link.expiresAt)}`}
                                    className="text-xs font-medium px-2 py-1 rounded-md border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-50"
                                >
                                    {revokingId === link.id ? 'Revoking…' : 'Revoke'}
                                </button>
                            </li>
                        ))}
                    </ul>
                </>
            )}
            {error && <p className="mt-2 text-sm text-red-500">{error}</p>}
        </div>
    );
};

export default ShareModal;

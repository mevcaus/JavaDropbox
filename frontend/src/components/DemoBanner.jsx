import { Info } from 'lucide-react';
import { useDemoInfo } from '../hooks/useDemoInfo';
import { formatSize } from '../utils/format';

const timeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });

// Tells visitors to the public demo what to expect; renders nothing on an ordinary install.
const DemoBanner = () => {
    const demo = useDemoInfo();
    if (!demo) return null;

    const limits = [`files up to ${formatSize(demo.maxUploadBytes)}`];
    if (demo.storageLimitBytes) limits.push(`${formatSize(demo.storageLimitBytes)} in total`);

    return (
        <div className="flex items-start gap-2 bg-blue-50 border-b border-blue-200 px-4 py-2 text-sm text-blue-900">
            <Info className="h-4 w-4 mt-0.5 flex-shrink-0" aria-hidden="true" />
            <p>
                Public demo: everyone shares this account. Uploads are limited to {limits.join(', ')}, and
                everything is reset daily at {timeFormatter.format(new Date(demo.nextReset))} your time.
            </p>
        </div>
    );
};

export default DemoBanner;

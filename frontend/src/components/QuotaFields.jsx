import { QUOTA_UNITS } from '../utils/quota';

/** An amount and a unit for a storage quota; an empty amount means no limit. */
const QuotaFields = ({ id, amount, unit, onAmountChange, onUnitChange, disabled }) => (
    <div>
        <label htmlFor={id} className="block text-sm font-medium text-gray-700 mb-1">
            Storage quota
        </label>
        <div className="flex gap-2">
            <input
                id={id}
                type="number"
                min="0"
                step="any"
                inputMode="decimal"
                placeholder="No limit"
                value={amount}
                readOnly={disabled}
                onChange={(e) => onAmountChange(e.target.value)}
                className="block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
            />
            <select
                aria-label="Quota unit"
                value={unit}
                disabled={disabled}
                onChange={(e) => onUnitChange(e.target.value)}
                className="rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
            >
                {QUOTA_UNITS.map((u) => (
                    <option key={u.label} value={u.label}>
                        {u.label}
                    </option>
                ))}
            </select>
        </div>
        <p className="mt-1 text-xs text-gray-500">
            Previous versions of files count toward it. Leave it empty for no limit.
        </p>
    </div>
);

export default QuotaFields;

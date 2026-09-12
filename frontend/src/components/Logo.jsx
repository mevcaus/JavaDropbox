import horizontalColor from '../assets/logo/javadropbox-horizontal-color.png';
import horizontalWhite from '../assets/logo/javadropbox-horizontal-white.png';
import markColor from '../assets/logo/javadropbox-mark-color.png';
import markWhite from '../assets/logo/javadropbox-mark-white.png';

// The brand kit is fixed-size png rather than svg, so each asset here is a few times larger than
// the size it renders at and is scaled down by css. That keeps it sharp on hidpi screens without
// shipping the largest export of each logo.
const VARIANTS = {
    color: { lockup: horizontalColor, mark: markColor },
    white: { lockup: horizontalWhite, mark: markWhite },
};

// `white` is the all-white version of the logo, for dark surfaces such as the sidebar.
const Logo = ({ collapsed = false, variant = 'color', className = '' }) => {
    const { lockup, mark } = VARIANTS[variant] ?? VARIANTS.color;

    return (
        <div className={`flex items-center ${collapsed ? 'justify-center' : ''} ${className}`}>
            <img
                src={collapsed ? mark : lockup}
                alt="JavaDropbox"
                className={collapsed ? 'h-10 w-auto' : 'h-8 w-auto'}
            />
        </div>
    );
};

export default Logo;

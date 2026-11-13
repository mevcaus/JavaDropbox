import boxLayer from '../assets/logo/javadropbox-mark-box.png';
import steamLayer from '../assets/logo/javadropbox-mark-steam.png';
import wordmark from '../assets/logo/javadropbox-wordmark-color.png';

// The brand kit only ships flattened png, so the mark is split into its two colour regions -- the
// box, and the steam rising out of it -- and stacked back up here. Keeping them as separate layers
// is what lets the steam be displaced and drifted on its own while the box stays put; a single
// flattened image could only ever be moved as a whole.
//
// The drift is deliberately small. In the original artwork the steam crosses in front of the box's
// top corner, so moving it far would uncover the gap it is hiding.
const AnimatedLogo = ({ className = '' }) => (
    <div className={`flex flex-col items-center ${className}`}>
        {/* One filter instance for the page; Login and Setup are never mounted at the same time. */}
        <svg aria-hidden="true" focusable="false" width="0" height="0" className="absolute">
            <filter id="jd-smoke" x="-35%" y="-35%" width="170%" height="170%">
                <feTurbulence
                    type="fractalNoise"
                    baseFrequency="0.013 0.021"
                    numOctaves="2"
                    seed="7"
                    result="noise"
                >
                    <animate
                        attributeName="baseFrequency"
                        dur="14s"
                        repeatCount="indefinite"
                        values="0.013 0.021; 0.019 0.029; 0.013 0.021"
                    />
                </feTurbulence>
                <feDisplacementMap
                    in="SourceGraphic"
                    in2="noise"
                    scale="6"
                    xChannelSelector="R"
                    yChannelSelector="G"
                />
            </filter>
        </svg>

        <div className="relative h-24 w-[75px]">
            <img
                src={boxLayer}
                alt=""
                aria-hidden="true"
                className="absolute inset-0 h-full w-full object-contain"
            />
            <img
                src={steamLayer}
                alt=""
                aria-hidden="true"
                className="jd-steam absolute inset-0 h-full w-full object-contain"
            />
        </div>

        <img src={wordmark} alt="JavaDropbox" className="mt-3 w-40" />
    </div>
);

export default AnimatedLogo;

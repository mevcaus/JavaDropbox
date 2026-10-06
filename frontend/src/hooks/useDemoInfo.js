import { useEffect, useState } from 'react';
import { getDemoInfo } from '../services/demo';

// The demo's account and limits once they have loaded; null before that and on ordinary installs.
export const useDemoInfo = () => {
    const [info, setInfo] = useState(null);

    useEffect(() => {
        let cancelled = false;
        getDemoInfo().then((loaded) => !cancelled && setInfo(loaded));
        return () => {
            cancelled = true;
        };
    }, []);

    return info;
};

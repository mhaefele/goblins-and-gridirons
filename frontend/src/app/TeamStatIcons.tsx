type IconProps = {
    className?: string;
    title?: string;
};

const svgProps = {
    viewBox: "0 0 24 24",
    fill: "none",
    xmlns: "http://www.w3.org/2000/svg",
    "aria-hidden": true as const,
};

/** Lightning bolt — Speed. */
export function SpeedIcon({ className, title }: IconProps) {
    return (
        <svg className={className} {...svgProps}>
            {title ? <title>{title}</title> : null}
            <path
                d="M13 2 4.5 13.5h6.2L9.8 22 19.5 10.2h-6.4L13 2Z"
                fill="currentColor"
                stroke="currentColor"
                strokeWidth="1.1"
                strokeLinejoin="round"
            />
        </svg>
    );
}

/** Fist — Bash. */
export function BashIcon({ className, title }: IconProps) {
    return (
        <svg className={className} {...svgProps}>
            {title ? <title>{title}</title> : null}
            <path
                d="M8.2 11.2V7.4a1.6 1.6 0 0 1 3.2 0v2.2M11.4 9.6V6.2a1.5 1.5 0 0 1 3 0v4M14.4 10.2V7.8a1.4 1.4 0 1 1 2.8 0v5.4c0 2.6-1.5 5-4.2 6.3l-.8.4c-1.7.8-3.7.8-5.4 0l-.7-.3C4.4 18.4 3 16.2 3 13.7v-1.8a1.8 1.8 0 0 1 3.2-1.1"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
            />
            <path d="M8.2 14.2h7.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
    );
}

/** Boot — Agility. */
export function AgilityIcon({ className, title }: IconProps) {
    return (
        <svg className={className} {...svgProps}>
            {title ? <title>{title}</title> : null}
            <path
                d="M5.2 16.8c1.4-1.1 3.4-1.6 5.6-1.4l2.1.2c1.5.2 2.9-.2 3.9-1.1l1.4-1.3c.5-.5 1.3-.4 1.6.3l.5 1.2c.3.8-.1 1.7-.9 2.1l-4.2 2.1c-1.4.7-3 .9-4.5.5L5.8 18.3c-.7-.2-1-.9-.6-1.5Z"
                fill="currentColor"
                opacity="0.9"
            />
            <path
                d="M8.4 14.8 10 6.6c.2-.9 1.1-1.5 2-1.3l.7.2c.7.2 1.2.8 1.2 1.5v4.4"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
            />
            <path d="M4.8 18.8h13.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" opacity="0.55" />
        </svg>
    );
}

/** Shield — Armor. */
export function ArmorIcon({ className, title }: IconProps) {
    return (
        <svg className={className} {...svgProps}>
            {title ? <title>{title}</title> : null}
            <path
                d="M12 2.5 5.5 5.2v5.3c0 4.4 2.9 7.9 6.5 9 3.6-1.1 6.5-4.6 6.5-9V5.2L12 2.5Z"
                fill="currentColor"
                opacity="0.22"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinejoin="round"
            />
            <path d="M12 6.2v9.2M9.2 10.4h5.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
    );
}

/** Small radar mesh accent for the ratings block header. */
export function RadarMeshIcon({ className, title }: IconProps) {
    return (
        <svg className={className} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
            {title ? <title>{title}</title> : null}
            <polygon
                points="12,3 20,8.5 17,18.5 7,18.5 4,8.5"
                stroke="currentColor"
                strokeWidth="1.2"
                fill="currentColor"
                fillOpacity="0.08"
            />
            <polygon points="12,7 16.5,10 15,15.5 9,15.5 7.5,10" stroke="currentColor" strokeWidth="1" fill="none" opacity="0.7" />
            <circle cx="12" cy="12" r="1.2" fill="currentColor" />
            <path d="M12 3v18M4 8.5h16M7 18.5 17 8.5M17 18.5 7 8.5" stroke="currentColor" strokeWidth="0.7" opacity="0.35" />
        </svg>
    );
}

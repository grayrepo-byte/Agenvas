import horizontalLogo from "../../assets/brand/agenvas-horizontal.png";
import squareLogo from "../../assets/brand/agenvas-square.png";
import "./BrandLogo.css";

type BrandLogoVariant = "horizontal" | "square";

const LOGO_ASSETS = {
  horizontal: { src: horizontalLogo, width: 2172, height: 724 },
  square: { src: squareLogo, width: 1254, height: 1254 },
} satisfies Record<BrandLogoVariant, { src: string; width: number; height: number }>;

/** Use decorative logos when the enclosing link already names its destination. */
export function BrandLogo({ variant = "horizontal", decorative = false, className = "" }: {
  variant?: BrandLogoVariant;
  decorative?: boolean;
  className?: string;
}) {
  const asset = LOGO_ASSETS[variant];
  return <span className={`brand-logo brand-logo--${variant} ${className}`}>
    <img src={asset.src} alt={decorative ? "" : "agenvas"} width={asset.width} height={asset.height}
      draggable={false} decoding="async" />
  </span>;
}

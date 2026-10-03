import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { loadIdentity, saveIdentity, setRequestIdentity, type Identity, type Role } from "./api";

interface IdentityContextValue {
  identity: Identity;
  setIdentity: (identity: Identity) => void;
  can: (...roles: Role[]) => boolean;
}

const IdentityContext = createContext<IdentityContextValue | null>(null);

export function IdentityProvider({ children, initial }: { children: ReactNode; initial?: Identity }) {
  const [identity, setState] = useState<Identity>(() => {
    const value = initial ?? loadIdentity();
    setRequestIdentity(value);
    return value;
  });
  const value = useMemo<IdentityContextValue>(
    () => ({
      identity,
      setIdentity: (next) => {
        setRequestIdentity(next);
        saveIdentity(next);
        setState(next);
      },
      // UI affordances only; every service enforces roles server-side.
      can: (...roles) => identity.roles.includes("ADMIN") || roles.some((r) => identity.roles.includes(r)),
    }),
    [identity],
  );
  return <IdentityContext.Provider value={value}>{children}</IdentityContext.Provider>;
}

export function useIdentity(): IdentityContextValue {
  const value = useContext(IdentityContext);
  if (!value) throw new Error("useIdentity outside IdentityProvider");
  return value;
}

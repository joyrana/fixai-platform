import { useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { AUTH_MODE, ROLES, type Role } from "../api";
import { useIdentity } from "../identity";

const NAV = [
  { to: "/", label: "Overview", end: true },
  { to: "/runs", label: "Certification runs" },
  { to: "/workflows", label: "AI workflows" },
  { to: "/brokers", label: "Brokers & sessions" },
  { to: "/approvals", label: "Approvals" },
  { to: "/knowledge", label: "Knowledge" },
  { to: "/audit", label: "Audit log" },
];

export function Layout() {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <img src="/favicon.svg" alt="" width={28} height={28} />
          <div>
            <strong>FIXAI</strong>
            <span>Certification platform</span>
          </div>
        </div>
        <nav aria-label="Main">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => (isActive ? "active" : "")}>
              {item.label}
            </NavLink>
          ))}
        </nav>
        {AUTH_MODE === "dev" && <IdentitySwitcher />}
      </aside>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

function IdentitySwitcher() {
  const { identity, setIdentity } = useIdentity();
  const [user, setUser] = useState(identity.user);
  const toggle = (role: Role) => {
    const roles = identity.roles.includes(role) ? identity.roles.filter((r) => r !== role) : [...identity.roles, role];
    if (roles.length) setIdentity({ user: identity.user, roles });
  };
  return (
    <div className="identity" aria-label="Development identity">
      <p className="eyebrow">Dev identity</p>
      <label>
        User
        <input
          value={user}
          onChange={(e) => setUser(e.target.value)}
          onBlur={() => user.trim() && setIdentity({ user: user.trim(), roles: identity.roles })}
          pattern="[A-Za-z0-9._@-]{1,64}"
        />
      </label>
      <div className="roles">
        {ROLES.map((role) => (
          <label key={role} className="check">
            <input type="checkbox" checked={identity.roles.includes(role)} onChange={() => toggle(role)} />
            {role.replaceAll("_", " ").toLowerCase()}
          </label>
        ))}
      </div>
      <p className="hint">Sent as X-Dev-User / X-Dev-Roles. Services accept these only with security disabled.</p>
    </div>
  );
}

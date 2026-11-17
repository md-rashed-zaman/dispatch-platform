import { createContext, useContext } from "react";

/** Work orders the live stream changed in the last moment, with when (so a second change flashes again). */
export const RecentChanges = createContext<Map<string, number>>(new Map());

export const useRecentChanges = () => useContext(RecentChanges);

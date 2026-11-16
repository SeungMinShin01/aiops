import { setupWorker } from "msw/browser";
import { adminHandlers } from "./adminHandlers";
import { handlers } from "./handlers";

export const worker = setupWorker(...handlers, ...adminHandlers);
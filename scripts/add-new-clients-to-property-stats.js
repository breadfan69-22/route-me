/**
 * Add New Clients to Property Stats Sheet
 *
 * Run this script from the Apps Script editor attached to the
 * Property Stats spreadsheet (1WV5ct-a6HfujHKIbTPf7YcTeIqjP8_SIF9jycm10saA).
 *
 * It reads ALL names from this year's client list sheet, compares them
 * (case-insensitive) against existing names in the Property Stats sheet,
 * and appends any new clients with their address pre-filled.
 *
 * HOW TO USE:
 * 1. Open Property Stats spreadsheet
 * 2. Extensions → Apps Script
 * 3. Paste this code (or add as a new .gs file)
 * 4. Run `addNewClientsToPropertyStats`
 * 5. Grant any permissions it requests
 * 6. Check the execution log for a summary
 *
 * NOTE: This does NOT delete anything — it only appends new rows.
 */

// ── Configuration ──────────────────────────────────────────────────────────────
var CLIENT_LIST_SHEET_ID = "1yHe6BUUVBV-5PEEXwZolPK-d-kW6x6ZGrcnDfhR-zOY";

// Column indices in the CLIENT LIST sheet (0-based)
var CL_NAME_COL = 0;   // Column A = Name
var CL_ADDR_COL = 2;   // Column C = Address

// Column indices in the PROPERTY STATS sheet (0-based)
var PS_NAME_COL = 0;    // Column A = Name
var PS_ADDR_COL = 1;    // Column B = Address
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Normalize a name for comparison: lowercase, collapse internal whitespace,
 * and strip punctuation differences (period, comma spacing) so that minor
 * formatting variations between sheets don't produce false mismatches.
 * e.g. "Wagner,  Mary" and "Wagner, Mary" both become "wagner mary"
 */
function normalizeName(name) {
    return name
        .toString()
        .trim()
        .toLowerCase()
        .replace(/[.,]/g, " ")      // treat commas/periods as spaces
        .replace(/\s+/g, " ")       // collapse multiple spaces
        .trim();
}

/**
 * Main function — run this one.
 */
function addNewClientsToPropertyStats() {
    // ── 1. Read existing names from Property Stats (this spreadsheet) ──
    var psSheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
    var psLastRow = psSheet.getLastRow();

    var existingNames = {};  // lowercase name → true
    if (psLastRow > 1) {
        var psNames = psSheet.getRange(2, PS_NAME_COL + 1, psLastRow - 1, 1).getValues();
        for (var i = 0; i < psNames.length; i++) {
            var name = psNames[i][0].toString().trim();
            if (name !== "") {
                existingNames[name.toLowerCase()] = true;
            }
        }
    }

    Logger.log("Property Stats has " + Object.keys(existingNames).length + " existing clients.");

    // ── 2. Read all names + addresses from this year's client list ──
    var clSS = SpreadsheetApp.openById(CLIENT_LIST_SHEET_ID);
    var clSheet = clSS.getSheets()[0];
    var clLastRow = clSheet.getLastRow();

    if (clLastRow < 2) {
        Logger.log("Client list sheet is empty — nothing to do.");
        return;
    }

    // Read columns A through C (Name, Notes, Address)
    var clData = clSheet.getRange(2, 1, clLastRow - 1, Math.max(CL_NAME_COL, CL_ADDR_COL) + 1).getValues();

    // ── 3. Find new clients ──
    var newClients = [];  // [{name, address}]
    var skipped = [];     // names we skipped (sold/deceased/empty)

    for (var r = 0; r < clData.length; r++) {
        var rawName = clData[r][CL_NAME_COL].toString().trim();
        var rawAddr = clData[r][CL_ADDR_COL].toString().trim();

        // Skip blank names
        if (rawName === "") continue;

        // Skip clients marked as sold/deceased/cancelled in name itself
        var nameLower = rawName.toLowerCase();
        if (nameLower.indexOf("sold") !== -1 ||
            nameLower.indexOf("deceased") !== -1 ||
            nameLower.indexOf("cancel") !== -1) {
            skipped.push(rawName + " (status keyword)");
            continue;
        }

        // Check if already exists in Property Stats
        if (existingNames[nameLower]) {
            continue;  // Already in the sheet — skip
        }

        newClients.push({ name: rawName, address: rawAddr });
        // Mark as seen so we don't add duplicates from the client list itself
        existingNames[nameLower] = true;
    }

    Logger.log("Found " + newClients.length + " new client(s) to add.");
    if (skipped.length > 0) {
        Logger.log("Skipped " + skipped.length + ": " + skipped.join(", "));
    }

    if (newClients.length === 0) {
        Logger.log("All clients already exist in Property Stats. Nothing to add.");
        return;
    }

    // ── 4. Append new clients to Property Stats ──
    // Build rows: [Name, Address, "", "", "", "", "", "", ""]
    // Matches columns: Name | Address | Lawn Size | Sun/Shade | Wind Exposure | Steep Slopes | Irrigation | Notes | Last Updated
    var newRows = [];
    for (var j = 0; j < newClients.length; j++) {
        newRows.push([
            newClients[j].name,
            newClients[j].address,
            "",  // Lawn Size
            "",  // Sun/Shade
            "",  // Wind Exposure
            "",  // Steep Slopes
            "",  // Irrigation
            "",  // Notes
            ""   // Last Updated
        ]);
    }

    var startRow = psSheet.getLastRow() + 1;
    psSheet.getRange(startRow, 1, newRows.length, newRows[0].length).setValues(newRows);

    // ── 5. Log results ──
    Logger.log("✅ Added " + newClients.length + " new client(s) starting at row " + startRow + ":");
    for (var k = 0; k < newClients.length; k++) {
        Logger.log("  " + (k + 1) + ". " + newClients[k].name + " — " + (newClients[k].address || "(no address)"));
    }
}

/**
 * Preview only — shows what WOULD be added without writing anything.
 * Useful for a dry run before committing.
 */
function previewNewClients() {
    var psSheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
    var psLastRow = psSheet.getLastRow();

    var existingNames = {};
    if (psLastRow > 1) {
        var psNames = psSheet.getRange(2, PS_NAME_COL + 1, psLastRow - 1, 1).getValues();
        for (var i = 0; i < psNames.length; i++) {
            var name = psNames[i][0].toString().trim();
            if (name !== "") {
                existingNames[name.toLowerCase()] = true;
            }
        }
    }

    var clSS = SpreadsheetApp.openById(CLIENT_LIST_SHEET_ID);
    var clSheet = clSS.getSheets()[0];
    var clLastRow = clSheet.getLastRow();

    if (clLastRow < 2) {
        Logger.log("Client list sheet is empty.");
        return;
    }

    var clData = clSheet.getRange(2, 1, clLastRow - 1, Math.max(CL_NAME_COL, CL_ADDR_COL) + 1).getValues();
    var newClients = [];

    for (var r = 0; r < clData.length; r++) {
        var rawName = clData[r][CL_NAME_COL].toString().trim();
        var rawAddr = clData[r][CL_ADDR_COL].toString().trim();

        if (rawName === "") continue;

        var nameLower = rawName.toLowerCase();
        if (nameLower.indexOf("sold") !== -1 ||
            nameLower.indexOf("deceased") !== -1 ||
            nameLower.indexOf("cancel") !== -1) {
            continue;
        }

        if (!existingNames[nameLower]) {
            newClients.push({ name: rawName, address: rawAddr });
            existingNames[nameLower] = true;
        }
    }

    Logger.log("=== DRY RUN — " + newClients.length + " new client(s) would be added ===");
    for (var k = 0; k < newClients.length; k++) {
        Logger.log("  " + (k + 1) + ". " + newClients[k].name + " — " + (newClients[k].address || "(no address)"));
    }
    Logger.log("=== Run addNewClientsToPropertyStats() to actually write them ===");
}

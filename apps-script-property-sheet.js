/**
 * RouteMe Property Specs Google Sheets Script
 *
 * Deploy this on a separate Google Sheet that tracks property attributes.
 * The app writes estimated lawn sizes here after completing a service.
 *
 * EXPECTED COLUMNS (row 1 headers):
 *   A: Client Name (must match route sheet names exactly)
 *   B: Lawn Size (sqft, written by the app)
 *   ... add more property columns as needed
 *
 * HOW LAWN SIZE IS CALCULATED:
 *   Spray steps (2 & 5) — fixed method rates:
 *     Hose  = 1 gal / 1,000 sqft
 *     PG    = 1 gal / 5,500 sqft
 *     sqft  = (hoseGal × 1,000) + (pgGal × 5,500)
 *     Both methods can be used on one jobsite.
 *
 *   Granular steps (1, 3, 4, 6, Grub) — configurable rate:
 *     sqft  = (lbsUsed / rate) × 1,000
 *     where rate = lbs/1,000sqft set in app's Application Rates dialog.
 *
 * DEPLOYMENT (same as route sheet script):
 * 1. Open your Property Specs Google Sheet
 * 2. Extensions → Apps Script
 * 3. Paste this code (replace any existing code)
 * 4. Click "Deploy" → "New deployment"
 * 5. Type: "Web app"
 * 6. Execute as: "Me"
 * 7. Who has access: "Anyone"
 * 8. Click "Deploy" and copy the URL
 * 9. (Future) Paste into RouteMe's property sheet URL field
 *
 * NOTE: Currently the app writes Lawn Size through the main route sheet
 * script's writeBackRaw(clientName, "Lawn Size", value). If you want a
 * dedicated property sheet, deploy this script on that sheet and point
 * the app at the new URL once a property-sheet URL setting is added.
 */

function doGet(e) {
    try {
        if (e.parameter && e.parameter.action === "exportAll") {
            return jsonResponse(exportAll());
        }
        if (e.parameter && e.parameter.clientName && e.parameter.column && e.parameter.value) {
            var data = {
                clientName: e.parameter.clientName,
                column: e.parameter.column,
                value: e.parameter.value
            };
            var result = updateCell(data);
            return jsonResponse(result);
        }
        return jsonResponse({
            status: "ok",
            message: "RouteMe Property Sheet script is running.",
            timestamp: new Date().toISOString()
        });
    } catch (error) {
        return jsonResponse({ status: "error", message: error.message });
    }
}

function exportAll() {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheets()[0];
    var lastRow = sheet.getLastRow();
    var lastCol = sheet.getLastColumn();
    if (lastRow < 2) return { status: "ok", rows: [] };

    var headers = sheet.getRange(1, 1, 1, lastCol).getValues()[0]
        .map(function (h) { return h.toString().trim(); });
    var data = sheet.getRange(2, 1, lastRow - 1, lastCol).getValues();
    var rows = [];

    for (var r = 0; r < data.length; r++) {
        var name = data[r][0].toString().trim();
        if (!name) continue;
        var obj = {};
        for (var c = 0; c < headers.length; c++) {
            if (headers[c]) obj[headers[c]] = data[r][c].toString().trim();
        }
        rows.push(obj);
    }
    return { status: "ok", rows: rows };
}

function doPost(e) {
    try {
        if (!e.postData || !e.postData.contents) {
            return jsonResponse({ status: "error", message: "No POST data" });
        }
        var data = JSON.parse(e.postData.contents);

        // Handle "addClientRow" action — adds a new row if client doesn't exist
        if (data.action === "addClientRow") {
            if (!data.clientName) {
                return jsonResponse({ status: "error", message: "Missing clientName for addClientRow" });
            }
            return jsonResponse(addClientRow(data.clientName, data.address || ""));
        }

        // Default: update a cell (requires clientName, column, value)
        if (!data.clientName || !data.column || !data.value) {
            return jsonResponse({ status: "error", message: "Missing clientName, column, or value" });
        }
        return jsonResponse(updateCell(data));
    } catch (error) {
        return jsonResponse({ status: "error", message: error.message });
    }
}

/**
 * Adds a new client row if it doesn't already exist.
 * Idempotent — returns success if row already exists.
 */
function addClientRow(clientName, address) {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheets()[0];

    // Check if client already exists
    var names = sheet.getRange(1, 1, sheet.getLastRow(), 1).getValues();
    var searchName = clientName.toString().trim().toLowerCase();

    for (var r = 1; r < names.length; r++) {
        var cellName = names[r][0].toString().trim().toLowerCase();
        if (cellName === searchName) {
            return { status: "ok", message: "Client already exists", row: r + 1 };
        }
    }

    // Find the Address column (if any)
    var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
    var addressColIndex = -1;
    for (var i = 0; i < headers.length; i++) {
        var header = headers[i].toString().trim().toLowerCase();
        if (header === "address") {
            addressColIndex = i + 1;
            break;
        }
    }

    // Add new row at the end
    var newRow = sheet.getLastRow() + 1;
    sheet.getRange(newRow, 1).setValue(clientName);
    if (addressColIndex > 0 && address) {
        sheet.getRange(newRow, addressColIndex).setValue(address);
    }

    return { status: "ok", message: "Added new client row", row: newRow };
}

function updateCell(data) {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheets()[0];

    var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
    var colIndex = -1;
    var searchColumn = data.column.toString().trim().toLowerCase();

    for (var i = 0; i < headers.length; i++) {
        var header = headers[i].toString().trim().toLowerCase().replace(/[√✓]/g, "").trim();
        if (header === searchColumn) {
            colIndex = i + 1;
            break;
        }
    }

    if (colIndex === -1) {
        return {
            status: "error",
            message: "Column not found: " + data.column,
            availableColumns: headers.filter(function (h) { return h.toString().trim() !== ""; })
        };
    }

    var names = sheet.getRange(1, 1, sheet.getLastRow(), 1).getValues();
    var rowIndex = -1;
    var searchName = data.clientName.toString().trim().toLowerCase();

    for (var r = 1; r < names.length; r++) {
        var cellName = names[r][0].toString().trim().toLowerCase();
        if (cellName === searchName) {
            rowIndex = r + 1;
            break;
        }
    }

    if (rowIndex === -1) {
        for (var r = 1; r < names.length; r++) {
            var cellName = names[r][0].toString().trim().toLowerCase();
            if (cellName.indexOf(searchName) !== -1 || searchName.indexOf(cellName) !== -1) {
                rowIndex = r + 1;
                break;
            }
        }
    }

    if (rowIndex === -1) {
        return { status: "error", message: "Client not found: " + data.clientName };
    }

    var cell = sheet.getRange(rowIndex, colIndex);
    var oldValue = cell.getValue();
    cell.setValue(data.value);

    return {
        status: "ok",
        message: "Updated",
        row: rowIndex,
        col: colIndex,
        oldValue: oldValue.toString(),
        newValue: data.value
    };
}

function jsonResponse(obj) {
    return ContentService.createTextOutput(JSON.stringify(obj))
        .setMimeType(ContentService.MimeType.JSON);
}

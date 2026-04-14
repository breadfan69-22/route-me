import sqlite3

conn = sqlite3.connect('routeme_debug.db')
c = conn.cursor()

# Check clients table
c.execute('SELECT COUNT(*), SUM(CASE WHEN latitude IS NOT NULL THEN 1 ELSE 0 END) FROM clients')
total, with_coords = c.fetchone()
print(f"Clients: {total} total, {with_coords or 0} with coordinates")

# Check geocode cache
c.execute('SELECT COUNT(*) FROM geocode_cache')
cache_count = c.fetchone()[0]
print(f"Geocode cache entries: {cache_count}")

# Sample some clients
print("\nSample clients (first 5):")
c.execute('SELECT name, address, latitude, longitude FROM clients LIMIT 5')
for row in c.fetchall():
    print(f"  {row[0]}: {row[1]} -> ({row[2]}, {row[3]})")

# Sample cache entries
if cache_count > 0:
    print("\nSample cache entries (first 5):")
    c.execute('SELECT addressKey, latitude, longitude FROM geocode_cache LIMIT 5')
    for row in c.fetchall():
        print(f"  {row[0][:50]}... -> ({row[1]}, {row[2]})")

conn.close()

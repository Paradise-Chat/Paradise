ulimit -n 65535

EXCLUDES=(
    --exclude 'node_modules'
    --exclude '.git'
    --exclude '.shadow-cljs'
    --exclude 'electron'
    --exclude '.#*'
    --exclude '#*#'
    --exclude '*~'
)

while true; do
    rsync -avzi "${EXCLUDES[@]}" . jaggar@192.168.4.209:/Users/jaggar/Code/Paradise/

    find . -type d \( -path "./node_modules" -o -path "./.git" -o -path "./.shadow-cljs" -o -path "./electron" \) -prune \
         -o -type f ! -name '.#*' ! -name '#*#' ! -name '*~' -print | \
        entr -d -p rsync -avzi "${EXCLUDES[@]}" . jaggar@192.168.4.209:/Users/jaggar/Code/Paradise/
done

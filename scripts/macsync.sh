while true; do
    find . -type d \( -path "./node_modules" -o -path "./.git" -o -path "./.shadow-cljs" -o -path "./electron" \) -prune -o -print | \
        entr -d rsync -avz --exclude 'node_modules' --exclude '.git' --exclude '.shadow-cljs' --exclude 'electron' . jaggar@192.168.4.210:/Users/jaggar/Code/Paradise/
done

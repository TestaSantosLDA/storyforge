// Drag-to-reorder for the queue (saved immediately), and a light check so another open tab
// reloads when the stored queue changes (Stage 0 scenario 13: the database wins).
(() => {
    const list = document.getElementById('queue');
    let state = document.querySelector('main').dataset.state;
    let dragged = null;

    if (list) {
        list.addEventListener('dragstart', e => {
            dragged = e.target.closest('li');
            dragged.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });
        list.addEventListener('dragend', () => {
            if (dragged) dragged.classList.remove('dragging');
            list.querySelectorAll('.over').forEach(li => li.classList.remove('over'));
        });
        list.addEventListener('dragover', e => {
            e.preventDefault();
            const target = e.target.closest('li');
            if (!target || target === dragged) return;
            const after = e.clientY > target.getBoundingClientRect().top + target.offsetHeight / 2;
            list.insertBefore(dragged, after ? target.nextSibling : target);
        });
        list.addEventListener('drop', async e => {
            e.preventDefault();
            const items = [...list.children];
            const position = items.indexOf(dragged) + 1;
            items.forEach((li, i) => li.querySelector('.pos').textContent = i + 1);
            try {
                const res = await fetch('/queue/reorder', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({storyId: Number(dragged.dataset.id), position})
                });
                if (!res.ok) throw new Error(await res.text());
                state = (await res.json()).state;
            } catch (err) {
                location.reload(); // show the stored order
            }
        });
    }

    setInterval(async () => {
        if (document.hidden || dragged?.classList.contains('dragging')) return;
        try {
            const res = await fetch('/queue/state');
            const now = (await res.json()).state;
            if (now !== state) location.reload();
            state = now;
        } catch (err) { /* server restarting; try again next tick */ }
    }, 4000);
})();

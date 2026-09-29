import './ConnectionPager.css';

export default function ConnectionPager({ page, total, size, onPageChange, loading, label }) {
  const pageCount = Math.ceil(total / size);
  if (pageCount <= 1) return null;

  return (
    <nav className="connection-pagination" aria-label={`Páginas de ${label}`}>
      <button type="button" onClick={() => onPageChange(page - 1)} disabled={loading || page === 0}
        aria-label={`Página anterior de ${label}`}>Anterior</button>
      <span aria-live="polite">{page + 1} de {pageCount}</span>
      <button type="button" onClick={() => onPageChange(page + 1)} disabled={loading || page + 1 >= pageCount}
        aria-label={`Página siguiente de ${label}`}>Siguiente</button>
    </nav>
  );
}

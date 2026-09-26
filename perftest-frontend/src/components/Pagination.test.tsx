import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Pagination from './Pagination'

describe('Pagination', () => {
  it('affiche le résumé et les boutons de page pour un petit nombre de pages', () => {
    render(
      <Pagination page={2} totalPages={3} onPageChange={() => {}} startIndex={11} endIndex={20} totalItems={25} itemLabel="scénarios" />
    )
    expect(screen.getByText('Affichage de 11 à 20 sur 25 scénarios')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '2' })).toHaveClass('active')
    expect(screen.getByRole('button', { name: '3' })).toBeInTheDocument()
  })

  it('insère des ellipses pour un grand nombre de pages, sans lister toutes les pages intermédiaires', () => {
    render(
      <Pagination page={10} totalPages={20} onPageChange={() => {}} startIndex={91} endIndex={100} totalItems={200} itemLabel="exécutions" />
    )
    expect(screen.getAllByText('...')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '9' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '10' })).toHaveClass('active')
    expect(screen.getByRole('button', { name: '11' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '20' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '5' })).not.toBeInTheDocument()
  })

  it('désactive "Précédent" sur la première page et "Suivant" sur la dernière', () => {
    const { rerender } = render(
      <Pagination page={1} totalPages={5} onPageChange={() => {}} startIndex={1} endIndex={10} totalItems={50} itemLabel="items" />
    )
    expect(screen.getByTitle('Précédent')).toBeDisabled()
    expect(screen.getByTitle('Suivant')).not.toBeDisabled()

    rerender(
      <Pagination page={5} totalPages={5} onPageChange={() => {}} startIndex={41} endIndex={50} totalItems={50} itemLabel="items" />
    )
    expect(screen.getByTitle('Suivant')).toBeDisabled()
  })

  it('appelle onPageChange avec le bon numéro au clic sur une page', () => {
    const onPageChange = vi.fn()
    render(
      <Pagination page={2} totalPages={3} onPageChange={onPageChange} startIndex={11} endIndex={20} totalItems={25} itemLabel="scénarios" />
    )
    fireEvent.click(screen.getByRole('button', { name: '3' }))
    expect(onPageChange).toHaveBeenCalledWith(3)
  })
})

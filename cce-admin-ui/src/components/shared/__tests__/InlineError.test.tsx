import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DescribedError } from '../../../utils/describeError';
import { InlineError } from '../InlineError';

function err(partial: Partial<DescribedError> & Pick<DescribedError, 'kind'>): DescribedError {
  return { code: 'CODE', status: 400, message: '', ...partial };
}

const RESULT_UNKNOWN = 'The result is unknown; the page was refreshed.';

describe('InlineError', () => {
  it('shows the forbidden text followed by the server message', () => {
    render(
      <InlineError
        error={err({
          kind: 'forbidden',
          code: 'FORBIDDEN',
          status: 403,
          message: 'Your roles do not allow DELETE /admin/v1/roles/r1.',
        })}
      />,
    );
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to do this.');
    expect(alert).toHaveTextContent('Your roles do not allow DELETE /admin/v1/roles/r1.');
  });

  it('shows only the forbidden text when there is no server message', () => {
    render(<InlineError error={err({ kind: 'forbidden', status: 403 })} />);
    expect(screen.getByRole('alert')).toHaveTextContent(
      /^You do not have permission to do this\.$/,
    );
  });

  it('shows the unauthenticated text', () => {
    render(
      <InlineError error={err({ kind: 'unauthenticated', status: 401, message: 'Expired.' })} />,
    );
    expect(
      screen.getByText(
        'The admin service did not accept your sign-in. Sign out and sign in again.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('Expired.')).not.toBeInTheDocument();
  });

  it('shows the network text', () => {
    render(
      <InlineError error={err({ kind: 'network', status: 0, code: 'CLIENT_NETWORK_ERROR' })} />,
    );
    expect(screen.getByText('Cannot reach the admin service.')).toBeInTheDocument();
  });

  it('shows the unexpected text with status and code', () => {
    render(
      <InlineError error={err({ kind: 'unexpected', status: 500, code: 'INTERNAL_ERROR' })} />,
    );
    expect(
      screen.getByText('Something went wrong (HTTP 500, INTERNAL_ERROR).'),
    ).toBeInTheDocument();
  });

  it.each([
    [404, 'NOT_FOUND'],
    [405, 'METHOD_NOT_ALLOWED'],
  ])('shows the API disagreement text for %i %s', (status, code) => {
    render(<InlineError error={err({ kind: 'unexpected', status, code })} />);
    expect(
      screen.getByText(
        `The console and the admin service disagree about the API (HTTP ${status}, ${code}).`,
      ),
    ).toBeInTheDocument();
  });

  it.each(['conflict', 'validation', 'notFound', 'syncFailed'] as const)(
    'shows the server message for %s',
    (kind) => {
      render(<InlineError error={err({ kind, message: 'The role is in use.' })} />);
      expect(screen.getByRole('alert')).toHaveTextContent(/^The role is in use\.$/);
    },
  );

  it('shows Retry only with onRetry, and calls it', async () => {
    const onRetry = vi.fn();
    const { rerender } = render(<InlineError error={err({ kind: 'network', status: 0 })} />);
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    rerender(<InlineError error={err({ kind: 'network', status: 0 })} onRetry={onRetry} />);
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('shows the "result is unknown" text for a failed mutation', () => {
    const { rerender } = render(
      <InlineError error={err({ kind: 'unexpected', status: 500 })} mutation />,
    );
    expect(screen.getByText(RESULT_UNKNOWN)).toBeInTheDocument();
    rerender(<InlineError error={err({ kind: 'network', status: 0 })} mutation />);
    expect(screen.getByText(RESULT_UNKNOWN)).toBeInTheDocument();
    rerender(<InlineError error={err({ kind: 'network', status: 0 })} />);
    expect(screen.queryByText(RESULT_UNKNOWN)).not.toBeInTheDocument();
  });

  it('does not show the "result is unknown" text for a refused mutation', () => {
    render(<InlineError error={err({ kind: 'forbidden', status: 403 })} mutation />);
    expect(screen.queryByText(RESULT_UNKNOWN)).not.toBeInTheDocument();
  });
});

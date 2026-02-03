import React, { useState, useEffect, useRef } from 'react';

interface SearchableSelectProps {
    id: string;
    value: string;
    options: string[];
    placeholder?: string;
    allLabel?: string;
    onFocus?: () => void;
    onChange: (value: string) => void;
}

const SearchableSelect: React.FC<SearchableSelectProps> = ({
    id,
    value,
    options,
    placeholder = 'Search...',
    allLabel = 'All',
    onFocus,
    onChange,
}) => {
    const [open, setOpen] = useState(false);
    const [search, setSearch] = useState('');
    const wrapperRef = useRef<HTMLDivElement>(null);
    const searchInputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        const handleClickOutside = (e: MouseEvent) => {
            if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
                setOpen(false);
                setSearch('');
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    useEffect(() => {
        if (open && searchInputRef.current) {
            searchInputRef.current.focus();
        }
    }, [open]);

    const filtered = search
        ? options.filter(o => o.toLowerCase().includes(search.toLowerCase()))
        : options;

    const handleToggle = () => {
        if (!open && onFocus) {
            onFocus();
        }
        setOpen(!open);
        setSearch('');
    };

    const handleSelect = (val: string) => {
        onChange(val);
        setOpen(false);
        setSearch('');
    };

    const displayLabel = value || allLabel;

    return (
        <div ref={wrapperRef} className="position-relative">
            <button
                id={id}
                type="button"
                className="form-select text-start"
                onClick={handleToggle}
                style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            >
                {displayLabel}
            </button>
            {open && (
                <div
                    className="dropdown-menu show w-100 shadow-sm p-0"
                    style={{
                        position: 'absolute',
                        top: '100%',
                        left: 0,
                        zIndex: 1050,
                        maxHeight: '350px',
                        display: 'flex',
                        flexDirection: 'column',
                    }}
                >
                    <div className="p-2 border-bottom">
                        <input
                            ref={searchInputRef}
                            type="text"
                            className="form-control form-control-sm"
                            placeholder={placeholder}
                            value={search}
                            onChange={e => setSearch(e.target.value)}
                            autoComplete="off"
                        />
                    </div>
                    <div style={{ overflowY: 'auto', maxHeight: '280px' }}>
                        <button
                            type="button"
                            className={`dropdown-item${!value ? ' active' : ''}`}
                            onClick={() => handleSelect('')}
                        >
                            {allLabel}
                        </button>
                        {filtered.length === 0 && (
                            <div className="dropdown-item text-muted disabled">No matches</div>
                        )}
                        {filtered.map(option => (
                            <button
                                key={option}
                                type="button"
                                className={`dropdown-item${value === option ? ' active' : ''}`}
                                onClick={() => handleSelect(option)}
                                style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                            >
                                {option}
                            </button>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
};

export default SearchableSelect;

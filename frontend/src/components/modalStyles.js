// Written out in full rather than built from a colour name: Tailwind only generates the classes
// it finds as complete strings in the source.
const primaryBase =
    'w-full inline-flex justify-center items-center rounded-md border border-transparent shadow-sm px-4 py-2 text-base font-medium text-white focus:outline-none focus:ring-2 focus:ring-offset-2 sm:w-auto sm:text-sm disabled:opacity-50';

export const primaryButton = {
    blue: `${primaryBase} bg-blue-600 hover:bg-blue-700 focus:ring-blue-500`,
    green: `${primaryBase} bg-green-600 hover:bg-green-700 focus:ring-green-500`,
    red: `${primaryBase} bg-red-600 hover:bg-red-700 focus:ring-red-500`,
};

export const secondaryButton =
    'mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 sm:mt-0 sm:w-auto sm:text-sm disabled:opacity-50';
